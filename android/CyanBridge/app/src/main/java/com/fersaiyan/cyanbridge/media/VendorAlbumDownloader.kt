package com.fersaiyan.cyanbridge.media

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class VendorAlbumDownloader(
    private val destinationDir: File,
    private val requestTag: String,
) {
    private val activeCall = AtomicReference<Call?>(null)

    data class DownloadResult(
        val file: File? = null,
        val errorCode: Int = 0,
        val errorDetail: String? = null,
        val cancelled: Boolean = false,
    ) {
        val isSuccess: Boolean get() = file?.isFile == true
    }

    init {
        destinationDir.mkdirs()
    }

    suspend fun download(
        url: String,
        fileName: String,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null,
    ): DownloadResult = suspendCancellableCoroutine { continuation ->
        val safeName = File(fileName).name
        val output = File(destinationDir, safeName)
        output.delete()

        val request = Request.Builder()
            .url(url)
            .get()
            .tag(String::class.java, requestTag)
            .build()
        val call = CLIENT.newCall(request)
        activeCall.set(call)

        continuation.invokeOnCancellation {
            call.cancel()
            output.delete()
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeCall.compareAndSet(call, null)
                output.delete()
                if (continuation.isActive) {
                    continuation.resume(
                        DownloadResult(
                            errorDetail = e.message,
                            cancelled = call.isCanceled(),
                        )
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        activeCall.compareAndSet(call, null)
                        output.delete()
                        if (continuation.isActive) {
                            continuation.resume(
                                DownloadResult(
                                    errorCode = response.code,
                                    errorDetail = "HTTP ${response.code}",
                                )
                            )
                        }
                        return
                    }

                    val body = response.body
                    if (body == null) {
                        activeCall.compareAndSet(call, null)
                        output.delete()
                        if (continuation.isActive) {
                            continuation.resume(DownloadResult(errorDetail = "Empty response body"))
                        }
                        return
                    }

                    try {
                        val total = body.contentLength()
                        var downloaded = 0L
                        body.byteStream().use { input ->
                            output.outputStream().buffered(128 * 1024).use { out ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    out.write(buffer, 0, read)
                                    downloaded += read
                                    onProgress?.invoke(downloaded, total)
                                }
                            }
                        }
                        activeCall.compareAndSet(call, null)
                        if (continuation.isActive) {
                            continuation.resume(DownloadResult(file = output))
                        }
                    } catch (error: Exception) {
                        activeCall.compareAndSet(call, null)
                        output.delete()
                        if (continuation.isActive) {
                            continuation.resume(
                                DownloadResult(
                                    errorDetail = error.message,
                                    cancelled = call.isCanceled(),
                                )
                            )
                        }
                    }
                }
            }
        })
    }

    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    fun clear() {
        destinationDir.deleteRecursively()
    }

    private companion object {
        // AndroidNetworking in the vendor app delegates downloads to a normal OkHttp client.
        // Keep this client unbound so routing behavior remains vendor-like.
        val CLIENT = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .callTimeout(4, TimeUnit.MINUTES)
            .build()
    }
}
