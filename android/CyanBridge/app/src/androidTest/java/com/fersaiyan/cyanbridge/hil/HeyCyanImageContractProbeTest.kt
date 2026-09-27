package com.fersaiyan.cyanbridge.hil

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.BuildConfig
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.media.HeyCyanP2pPolicy
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.fersaiyan.cyanbridge.plugins.autodiary.AutoDiaryService
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryPreferences
import com.fersaiyan.cyanbridge.plugins.visualdiary.VisualDiaryService
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidPreferences
import com.fersaiyan.cyanbridge.plugins.walkingaid.WalkingAidService
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.ui.HeyCyanBleSetupTrace
import com.fersaiyan.cyanbridge.ui.wifi.p2p.WifiP2pManagerSingleton
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Observation only: never assembles, repairs or accepts a thumbnail as valid media. */
@RunWith(AndroidJUnit4::class)
class HeyCyanImageContractProbeTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sdk = LargeDataHandler.getInstance()
    private val report = JSONObject()
    private val captures = JSONArray()
    private val notifications = JSONArray()
    private val clock: Long = SystemClock.elapsedRealtime()
    private val runId = InstrumentationRegistry.getArguments().getString("contract_run_id")
        ?.takeIf { it.matches(Regex("contract_[A-Za-z0-9_-]{1,48}")) }
        ?: "contract_${System.currentTimeMillis()}"
    private val evidence = File(context.filesDir, "hil/image-contract/$runId")
    private val cameraDiagnostic = InstrumentationRegistry.getArguments()
        .getString("contract_camera_diagnostic") == "true"
    private val operatorPhotoDiagnostic = InstrumentationRegistry.getArguments()
        .getString("contract_operator_photo") == "true"
    private val onFaceAppDiagnostic = InstrumentationRegistry.getArguments()
        .getString("contract_on_face_capture") == "true"
    private val delayedCountDiagnostic = InstrumentationRegistry.getArguments()
        .getString("contract_delayed_count") == "true"
    private val expectedPriorPhotoCount = InstrumentationRegistry.getArguments()
        .getString("contract_expected_photo_count")?.toIntOrNull()
    private var listenerInstalled = false
    private var p2p: WifiP2pManagerSingleton? = null
    private var p2pCallback: WifiP2pManagerSingleton.WifiP2pCallback? = null
    private var p2pRegistered = false
    private var transferAttempted = false
    @Volatile private var quarantine = false
    @Volatile private var photoReady: CountDownLatch? = null
    @Volatile private var wifiIp: String? = null
    @Volatile private var photoReadyArmedAt: Long = Long.MAX_VALUE
    private var sdkSlotUnresolved = false

    private val listener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            val bytes = response.loadData.copyOf()
            synchronized(notifications) {
                notifications.put(JSONObject().put("ms", elapsed()).put("cmdType", cmdType)
                    .put("hex", bytes.hex()))
            }
            if (bytes.size > 6) when (bytes[6].toInt() and 255) {
                0x02 -> if (SystemClock.elapsedRealtime() >= photoReadyArmedAt) photoReady?.countDown()
                0x08 -> if (bytes.size >= 11) {
                    wifiIp = (7..10).joinToString(".") { (bytes[it].toInt() and 255).toString() }
                }
            }
        }
    }

    @Test fun observeTwoFreshCapturesAndTransferTransition() {
        assertTrue("This probe must never run in a release APK", BuildConfig.DEBUG)
        evidence.mkdirs()
        report.put("runId", runId).put("phone", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT).put("captures", captures)
            .put("notifications", notifications).put("transport", "vendor BLE SDK + Wi-Fi Direct HTTP")
            .put("contractApproved", false).put("cameraDiagnostic", cameraDiagnostic)
            .put("operatorPhotoDiagnostic", operatorPhotoDiagnostic)
            .put("onFaceAppDiagnostic", onFaceAppDiagnostic)
            .put("delayedCountDiagnostic", delayedCountDiagnostic)
        var owned = false
        var probeFailure: Throwable? = null
        try {
            assertTrue("Select at most one diagnostic mode",
                listOf(cameraDiagnostic, operatorPhotoDiagnostic, onFaceAppDiagnostic, delayedCountDiagnostic)
                    .count { it } <= 1)
            preflight()
            val lease = GlassesSessionCoordinator.tryAcquireLease(GlassesSession.MEDIA_SYNC)
                ?: error("Another glasses session or background SDK command is active")
            owned = true
            try {
                sdk.addOutDeviceListener(NOTIFY_ID, listener)
                listenerInstalled = true
                val stable = awaitStableBleSession()
                report.put("bleStableBeforeCapture", true)
                report.put("bleConnectionEvents", stable.connectionEvents)
                    .put("bleDiscoveryEvents", stable.discoveryEvents)
                    .put("bleSetupSequence", stable.sequence)
                val info = await(12_000, "device info") { done ->
                    sdk.syncDeviceInfo { _, rsp ->
                        synchronized(report) {
                            report.put("deviceInfoCallback", true)
                            if (rsp != null) {
                                report.put("bleHardware", rsp.hardwareVersion ?: "")
                                    .put("bleFirmware", rsp.firmwareVersion ?: "")
                                    .put("wifiHardware", rsp.wifiHardwareVersion ?: "")
                                    .put("wifiFirmware", rsp.wifiFirmwareVersion ?: "")
                            }
                        }
                        done()
                    }
                }
                assertTrue("No device-info response", info)
                assertTrue("Missing BLE firmware identifier", report.optString("bleFirmware").isNotBlank())

                val afterInfo = HeyCyanBleSetupTrace.snapshot()
                assertTrue("BLE setup changed during device-info request; no capture sent",
                    afterInfo.sequence == stable.sequence && BleOperateManager.getInstance().isConnected)

                if (cameraDiagnostic) {
                    diagnoseCameraOnce()
                    report.put("diagnosticComplete", true)
                    return
                }
                if (operatorPhotoDiagnostic) {
                    observeOperatorPhotoOnce()
                    report.put("diagnosticComplete", true)
                    return
                }
                if (onFaceAppDiagnostic) {
                    diagnoseOnFaceAppCaptureOnce()
                    report.put("diagnosticComplete", true)
                    return
                }
                if (delayedCountDiagnostic) {
                    observeDelayedMediaCount()
                    report.put("diagnosticComplete", true)
                    return
                }
                repeat(2) { ordinal ->
                    val capture = JSONObject().put("ordinal", ordinal + 1)
                    captures.put(capture)
                    captureAndObserve(ordinal + 1, capture)
                    probeTransfer(capture)
                    capture.put("cameraReadinessVerifiedAfterTeardown", true)
                }
                report.put("observationComplete", true)
                // A callback's first/last index and framing still require review against the saved raw bytes.
                // This observational test intentionally does not certify a strict completeness rule.
            } catch (failure: Throwable) {
                probeFailure = failure
                throw failure
            } finally {
                if (transferAttempted) {
                    try {
                        exitAndConfirmTeardown()
                    } catch (failure: Throwable) {
                        quarantine = true
                        report.put("cleanupFailure", failure.toString())
                        if (probeFailure == null) throw failure
                    }
                }
                if (listenerInstalled) {
                    try {
                        sdk.removeOutDeviceListener(NOTIFY_ID)
                    } catch (failure: Throwable) {
                        quarantine = true
                        report.put("listenerRemovalFailure", failure.toString())
                        if (probeFailure == null) throw failure
                    }
                }
                if (!quarantine) {
                    assertTrue("The hardware lease was invalidated", GlassesSessionCoordinator.release(lease))
                    owned = false
                }
            }
            assertFalse("P2P/SDK state unconfirmed; BLE reconnect required", quarantine)
        } finally {
            report.put("quarantined", quarantine).put("leaseHeld", owned)
            synchronized(notifications) {
                synchronized(report) {
                    File(evidence, "report.json").writeText(report.toString(2))
                }
            }
            android.util.Log.i(TAG, "evidence=$evidence observationComplete=${report.optBoolean("observationComplete")} quarantined=$quarantine")
        }
    }

    private fun preflight() {
        val explicitSerial = InstrumentationRegistry.getArguments().getString("contract_serial")
        assertTrue("Physical serial must be supplied by the ADB runner", !explicitSerial.isNullOrBlank())
        report.put("optionalOfficialPackage",
            InstrumentationRegistry.getArguments().getString("contract_official_package").orEmpty())
        assertEquals("Select HeyCyan on the dedicated phone", DeviceClass.HEY_CYAN, DeviceProfileStore.selectedClass(context))
        assertTrue("Glasses BLE did not reconnect during preflight", waitUntil(30_000) {
            BleOperateManager.getInstance().isConnected && BleOperateManager.getInstance().isReady
        })
        report.put("bleReady", BleOperateManager.getInstance().isReady)
        val address = DeviceManager.getInstance().deviceAddress
        val name = DeviceManager.getInstance().deviceName
        assertTrue("No paired glasses identity", !address.isNullOrBlank() && !name.isNullOrBlank())
        report.put("pairedName", name).put("pairedAddress", address)
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        for (required in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, permission)) {
            val granted = ContextCompat.checkSelfPermission(context, required) == PackageManager.PERMISSION_GRANTED
            report.put("permission:$required", granted)
            assertTrue("Missing $required", granted)
        }
        assertTrue("Wi-Fi is off", (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled)
        assertFalse("Auto audio capture must be disabled", AutoAudioCaptureService.isRunning())
        assertFalse("Auto audio capture is enabled and may restart", AutoAudioCapturePrefs.isEnabled(context))
        assertFalse("Visual Diary must be disabled", VisualDiaryService.isRunning())
        assertFalse("Visual Diary is enabled and may restart", VisualDiaryPreferences.isEnabled(context))
        assertFalse("Walking Aid must be disabled", WalkingAidService.isRunning())
        assertFalse("Walking Aid is enabled and may restart", WalkingAidPreferences.isEnabled(context))
        assertFalse("Auto Diary is enabled and may trigger a capture", AutoDiaryService.isEnabled(context))
        assertTrue("A glasses session is already active", GlassesSessionCoordinator.currentSession() == null)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertTrue("Another flow has bound the process to a network", cm.boundNetworkForProcess == null)
        assertFalse("A P2P group is already formed", groupFormed())
        report.put("preflight", "passed")
    }

    private fun captureAndObserve(ordinal: Int, record: JSONObject) {
        assertTrue("BLE disconnected before capture $ordinal", BleOperateManager.getInstance().isConnected)
        assertTrue("BLE services not ready before capture $ordinal", BleOperateManager.getInstance().isReady)
        assertFalse("Previous thumbnail response not isolated; reconnect required", quarantine || sdkSlotUnresolved)
        val ready = CountDownLatch(1)
        try {
            val ack = CountDownLatch(1)
            var code: Int? = null
            record.put("commandMs", elapsed())
            photoReadyArmedAt = SystemClock.elapsedRealtime()
            photoReady = ready
            val command = byteArrayOf(0x02, 0x01, 0x06, 0x02, 0x02)
            record.put("commandHex", command.hex())
            sdk.glassesControl(command) { cmdType, rsp ->
                code = rsp.errorCode
                record.put("ackCmdType", cmdType).put("ackDataType", rsp.dataType)
                ack.countDown()
            }
            assertTrue("No capture ACK; SDK slot may be live", ack.await(8, TimeUnit.SECONDS).also {
                if (!it) { quarantine = true; sdkSlotUnresolved = true }
            })
            record.put("ackError", code)
            record.put("ackNonzero", code != 0)
            // The official companion persists a photo after the same nonzero ACK. Capture
            // success is established only by later device evidence, never this ACK alone.
            assertTrue("No fresh 0x02 photo-ready event", ready.await(10, TimeUnit.SECONDS).also { if (!it) quarantine = true })
            record.put("photoReadyMs", elapsed())
            // Not an image validator. Preserve every callback's exact payload, even duplicates and terminators.
            val callbacks = JSONArray()
            record.put("thumbnailCallbacks", callbacks)
            val complete = CountDownLatch(1)
            val active = AtomicBoolean(true)
            val captureDir = File(evidence, "capture_$ordinal").apply { mkdirs() }
            var count = 0
            var bytes = 0L
            sdk.getPictureThumbnails { index, isComplete, data ->
                synchronized(callbacks) {
                    val sequence = ++count
                    val payload = data?.copyOf() ?: byteArrayOf()
                    bytes += payload.size
                    val item = JSONObject().put("sequence", sequence).put("ms", elapsed())
                        .put("index", index).put("complete", isComplete)
                        .put("nullPayload", data == null).put("length", payload.size)
                        .put("sha256", payload.sha256()).put("headHex", payload.take(16).toByteArray().hex())
                        .put("tailHex", payload.takeLast(16).toByteArray().hex())
                    if (active.get() && bytes <= MAX_THUMBNAIL_BYTES && count <= MAX_CALLBACKS) {
                        val file = "packet_%04d.bin".format(sequence)
                        File(captureDir, file).writeBytes(payload)
                        item.put("file", file)
                    } else {
                        quarantine = true
                        item.put(if (active.get()) "limitExceeded" else "lateCallback", true)
                    }
                    callbacks.put(item)
                    if (isComplete) complete.countDown()
                }
            }
            val received = complete.await(20, TimeUnit.SECONDS)
            // Observation window, NOT proof the SDK slot is isolated. Never approve the
            // packet contract based solely on the absence of late callbacks here.
            if (received) Thread.sleep(500)
            active.set(false)
            if (!received) {
                quarantine = true
                sdkSlotUnresolved = true // Do not reuse an unproven SDK response slot.
            }
            synchronized(callbacks) {
                record.put("callbackCount", count).put("callbackBytes", bytes)
                record.put("callbackIndices", JSONArray().apply {
                    for (i in 0 until callbacks.length()) put(callbacks.getJSONObject(i).getInt("index"))
                })
            }
            if (quarantine) sdkSlotUnresolved = true
            assertTrue("Thumbnail completion absent; reconnect before another request", received)
            assertTrue("Thumbnail callback evidence exceeded bounds", !quarantine && count > 0 && bytes > 0)
            android.util.Log.i(TAG, "capture=$ordinal callbacks=$count bytes=$bytes raw=$captureDir")
        } finally {
            photoReady = null
            photoReadyArmedAt = Long.MAX_VALUE
        }
    }

    /** A quiet window follows the LAST observed service-discovery/connection event. */
    private fun awaitStableBleSession(): HeyCyanBleSetupTrace.Snapshot {
        var stable: HeyCyanBleSetupTrace.Snapshot? = null
        val ready = waitUntil(30_000) {
            val snapshot = HeyCyanBleSetupTrace.snapshot()
            val ble = BleOperateManager.getInstance()
            val quietMs = SystemClock.elapsedRealtime() - snapshot.lastEventAtMs
            if (snapshot.discoveryEvents > 0 && snapshot.lastEvent == "servicesDiscovered" &&
                quietMs >= BLE_QUIET_MS && ble.isConnected && ble.isReady) {
                stable = snapshot
                true
            } else false
        }
        val snapshot = HeyCyanBleSetupTrace.snapshot()
        report.put("bleSetupAtGate", JSONObject()
            .put("sequence", snapshot.sequence).put("connectionEvents", snapshot.connectionEvents)
            .put("discoveryEvents", snapshot.discoveryEvents).put("lastEvent", snapshot.lastEvent)
            .put("quietMs", SystemClock.elapsedRealtime() - snapshot.lastEventAtMs))
        assertTrue("BLE setup never became quiet and ready; no capture sent", ready)
        assertEquals("BLE setup changed while arming capture", stable?.sequence, snapshot.sequence)
        return snapshot
    }

    /** One known UI camera command, no retries, no thumbnail request, no mode-changing guess. */
    private fun diagnoseCameraOnce() {
        val record = JSONObject().put("ordinal", 1).put("purpose", "known UI photo command")
        captures.put(record)
        val ready = CountDownLatch(1)
        val ack = CountDownLatch(1)
        val command = byteArrayOf(0x02, 0x01, 0x01)
        val before = HeyCyanBleSetupTrace.snapshot()
        assertTrue("BLE setup changed immediately before photo command",
            before.sequence == report.getLong("bleSetupSequence") &&
                SystemClock.elapsedRealtime() - before.lastEventAtMs >= BLE_QUIET_MS)
        record.put("commandHex", command.hex())
        record.put("bleSetupSequenceBeforeCommand", before.sequence)
        photoReady = ready
        photoReadyArmedAt = SystemClock.elapsedRealtime()
        try {
            record.put("commandMs", elapsed())
            sdk.glassesControl(command) { cmdType, rsp ->
                synchronized(record) {
                    record.put("ackCmdType", cmdType).put("ackDataType", rsp.dataType)
                        .put("ackGlassWorkType", rsp.glassWorkType)
                        .put("ackError", rsp.errorCode).put("ackWorkTypeIng", rsp.workTypeIng)
                        .put("ackMs", elapsed())
                }
                ack.countDown()
            }
            val responded = ack.await(10, TimeUnit.SECONDS)
            if (!responded) {
                quarantine = true
                sdkSlotUnresolved = true
                record.put("ackTimeout", true)
                return
            }
            // Even a rejected command could have caused a delayed capture. Observe, don't retry.
            record.put("photoReady", ready.await(8, TimeUnit.SECONDS))
            record.put("bleConnectedAfter", BleOperateManager.getInstance().isConnected)
            val after = HeyCyanBleSetupTrace.snapshot()
            record.put("bleSetupSequenceAfterCommand", after.sequence)
                .put("bleSetupChangedDuringCommand", after.sequence != before.sequence)
            android.util.Log.i(TAG, "camera diagnostic ack=${record.optInt("ackError")} " +
                "workType=${record.optInt("ackGlassWorkType")} state=${record.optInt("ackWorkTypeIng")} " +
                "photoReady=${record.optBoolean("photoReady")}")
        } finally {
            photoReady = null
            photoReadyArmedAt = Long.MAX_VALUE
        }
    }

    /** One app-triggered AI-photo command while the operator reports wearing the glasses. */
    private fun diagnoseOnFaceAppCaptureOnce() {
        val record = JSONObject().put("ordinal", 1).put("purpose", "single on-face app AI-photo capture")
        captures.put(record)
        val before = readMediaCounts("before", record)
        val setup = HeyCyanBleSetupTrace.snapshot()
        assertTrue("BLE changed during baseline; no capture sent",
            setup.sequence == report.getLong("bleSetupSequence") &&
                BleOperateManager.getInstance().isConnected && BleOperateManager.getInstance().isReady)
        val ready = CountDownLatch(1)
        val ack = CountDownLatch(1)
        val command = byteArrayOf(0x02, 0x01, 0x06, 0x02, 0x02)
        record.put("commandHex", command.hex()).put("bleSetupSequenceBeforeCommand", setup.sequence)
        photoReady = ready
        photoReadyArmedAt = SystemClock.elapsedRealtime()
        try {
            record.put("commandMs", elapsed())
            sdk.glassesControl(command) { cmdType, rsp ->
                synchronized(record) {
                    record.put("ackCmdType", cmdType).put("ackDataType", rsp.dataType)
                        .put("ackGlassWorkType", rsp.glassWorkType)
                        .put("ackError", rsp.errorCode).put("ackWorkTypeIng", rsp.workTypeIng)
                        .put("ackMs", elapsed())
                }
                ack.countDown()
            }
            val responded = ack.await(10, TimeUnit.SECONDS)
            if (!responded) {
                quarantine = true
                sdkSlotUnresolved = true
                record.put("ackTimeout", true)
                return
            }
            // A refusal can still be followed by a notification: observe once, never retry.
            record.put("photoReadyObserved", ready.await(10, TimeUnit.SECONDS))
        } finally {
            photoReady = null
            photoReadyArmedAt = Long.MAX_VALUE
        }
        val afterSetup = HeyCyanBleSetupTrace.snapshot()
        record.put("bleChangedDuringCommand", afterSetup.sequence != setup.sequence)
        if (afterSetup.sequence != setup.sequence || !BleOperateManager.getInstance().isConnected) {
            record.put("inconclusive", "BLE connection changed during capture")
            return
        }
        val after = readMediaCounts("after", record)
        record.put("photoCountDelta", after.first - before.first)
            .put("newPhotoCountObserved", after.first > before.first)
        android.util.Log.i(TAG, "on-face app capture ack=${record.optInt("ackError")} " +
            "ready=${record.optBoolean("photoReadyObserved")} photoCountDelta=${after.first - before.first}")
    }

    /** Read-only delayed check for a capture that may have persisted after its command ACK. */
    private fun observeDelayedMediaCount() {
        val record = JSONObject().put("ordinal", 1).put("purpose", "delayed read-only media-count check")
        captures.put(record)
        expectedPriorPhotoCount?.let { record.put("expectedPriorPhotoCount", it) }
        val counts = readMediaCounts("delayed", record)
        expectedPriorPhotoCount?.let { expected ->
            record.put("photoCountIncreaseSincePriorRun", counts.first > expected)
        }
        android.util.Log.i(TAG, "delayed read-only photoCount=${counts.first} expectedPrior=$expectedPriorPhotoCount")
    }

    /** Operator-assisted camera check. Never sends a photo or mode-control command. */
    private fun observeOperatorPhotoOnce() {
        val record = JSONObject().put("ordinal", 1).put("purpose", "single physical photo press")
        captures.put(record)
        val before = readMediaCounts("before", record)
        val setup = HeyCyanBleSetupTrace.snapshot()
        assertTrue("BLE changed during baseline; no operator window opened",
            setup.sequence == report.getLong("bleSetupSequence") && BleOperateManager.getInstance().isConnected)
        val ready = CountDownLatch(1)
        photoReadyArmedAt = SystemClock.elapsedRealtime()
        photoReady = ready
        record.put("operatorWindowOpenedMs", elapsed())
        android.util.Log.i(TAG, "OPERATOR_WINDOW_OPEN runId=$runId durationMs=$OPERATOR_WINDOW_MS " +
            "Press the glasses' physical PHOTO control ONCE now; do not use the phone app")
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "OPERATOR_WINDOW_OPEN: press the glasses physical PHOTO control ONCE now (30 seconds).\n")
        })
        try {
            record.put("photoReadyObserved", ready.await(OPERATOR_WINDOW_MS, TimeUnit.MILLISECONDS))
        } finally {
            record.put("operatorWindowClosedMs", elapsed())
            photoReady = null
            photoReadyArmedAt = Long.MAX_VALUE
            android.util.Log.i(TAG, "OPERATOR_WINDOW_CLOSED runId=$runId")
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("stream", "OPERATOR_WINDOW_CLOSED: do not press again.\n")
            })
        }
        val afterSetup = HeyCyanBleSetupTrace.snapshot()
        record.put("bleChangedDuringWindow", afterSetup.sequence != setup.sequence)
        if (afterSetup.sequence != setup.sequence || !BleOperateManager.getInstance().isConnected) {
            record.put("inconclusive", "BLE connection changed during operator window")
            return
        }
        val after = readMediaCounts("after", record)
        record.put("photoCountDelta", after.first - before.first)
            .put("videoCountDelta", after.second - before.second)
            .put("audioCountDelta", after.third - before.third)
        // A counter increase and/or 0x02 notify is supporting evidence only: without a
        // capture identifier the event could still belong to an external session.
        record.put("newPhotoCountObserved", after.first > before.first)
        android.util.Log.i(TAG, "operator photo observed=${record.optBoolean("photoReadyObserved")} " +
            "photoCountDelta=${after.first - before.first}")
    }

    private fun readMediaCounts(stage: String, record: JSONObject): Triple<Int, Int, Int> {
        val done = CountDownLatch(1)
        var counts: Triple<Int, Int, Int>? = null
        val command = byteArrayOf(0x02, 0x04) // Existing read-only Media Count UI command.
        record.put("${stage}MediaCommandHex", command.hex())
        sdk.glassesControl(command) { cmdType, rsp ->
            synchronized(record) {
                record.put("${stage}MediaCmdType", cmdType).put("${stage}MediaDataType", rsp.dataType)
                    .put("${stage}MediaPhotoCount", rsp.imageCount)
                    .put("${stage}MediaVideoCount", rsp.videoCount)
                    .put("${stage}MediaAudioCount", rsp.recordCount)
                    .put("${stage}MediaMs", elapsed())
            }
            if (rsp.dataType == 4) counts = Triple(rsp.imageCount, rsp.videoCount, rsp.recordCount)
            done.countDown()
        }
        val responded = done.await(8, TimeUnit.SECONDS)
        if (!responded) {
            quarantine = true
            sdkSlotUnresolved = true
            record.put("${stage}MediaTimeout", true)
        }
        assertTrue("$stage media count unavailable; no inference about photo creation", responded && counts != null)
        return requireNotNull(counts)
    }

    private fun probeTransfer(record: JSONObject) {
        assertFalse("Unsafe SDK state; refusing transfer", quarantine)
        transferAttempted = true
        wifiIp = null
        val manager = WifiP2pManagerSingleton.getInstance(context)
        p2p = manager
        manager.resetFailCount()
        manager.registerReceiver()
        p2pRegistered = true
        val connected = CountDownLatch(1)
        val address = requireNotNull(DeviceManager.getInstance().deviceAddress)
        val name = DeviceManager.getInstance().deviceName
        val callback = object : WifiP2pManagerSingleton.WifiP2pCallback {
            override fun onWifiP2pEnabled() = Unit
            override fun onWifiP2pDisabled() = Unit
            override fun onPeersChanged(peers: Collection<WifiP2pDevice>) {
                val matches = peers.filter {
                    HeyCyanP2pPolicy.matchesOfficialPeer(it.deviceName, name, address)
                }
                if (matches.size == 1 && !manager.isConnected() && !manager.isConnecting()) manager.connectToDevice(matches.single())
            }
            override fun onThisDeviceChanged(device: WifiP2pDevice) = Unit
            override fun onConnected(info: WifiP2pInfo) { if (info.groupFormed) connected.countDown() }
            override fun onDisconnected() = Unit
            override fun onPeerDiscoveryStarted() = Unit
            override fun onPeerDiscoveryFailed(reason: Int) = Unit
            override fun onConnectRequestSent() = Unit
            override fun onConnectRequestFailed(reason: Int) = Unit
            override fun connecting() = Unit
            override fun cancelConnect() = Unit
            override fun cancelConnectFail(reason: Int) = Unit
            override fun retryAlsoFailed() = Unit
        }
        p2pCallback = callback
        manager.addCallback(callback)
        manager.startPeerDiscovery(allowDeviceResetOnTimeout = false)
        val ack = CountDownLatch(1)
        var errorCode: Int? = null
        sdk.glassesControl(byteArrayOf(0x02, 0x01, 0x04)) { _, rsp -> errorCode = rsp.errorCode; ack.countDown() }
        assertTrue("Transfer-mode response absent", ack.await(12, TimeUnit.SECONDS).also {
            if (!it) { quarantine = true; sdkSlotUnresolved = true }
        })
        record.put("transferAckError", errorCode)
        assertEquals("Transfer mode rejected", 0, errorCode)
        assertTrue("P2P group did not form", connected.await(35, TimeUnit.SECONDS))
        assertTrue("Glasses never reported HTTP IP over BLE", waitUntil(12_000) { wifiIp != null })
        val ip = requireNotNull(wifiIp)
        record.put("bleWifiIp", ip)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val network = cm.allNetworks.firstOrNull { net ->
            cm.getLinkProperties(net)?.let { props ->
                val local = props.linkAddresses.map { it.address.hostAddress.orEmpty() }
                props.interfaceName?.contains("p2p", true) == true || local.any { it.startsWith("192.168.49.") }
            } == true
        } ?: error("No identifiable P2P network for HTTP routing")
        val url = URL("http://$ip/files/media.config")
        val connection = network.openConnection(url) as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            assertEquals("media.config HTTP status", 200, connection.responseCode)
            val body = connection.inputStream.use { input -> input.readNBytes(MAX_MANIFEST_BYTES + 1) }
            assertTrue("media.config empty or oversized", body.isNotEmpty() && body.size <= MAX_MANIFEST_BYTES)
            File(evidence, "manifest_${record.getInt("ordinal")}.bin").writeBytes(body)
            record.put("manifestBytes", body.size).put("manifestSha256", body.sha256())
        } finally {
            connection.disconnect()
        }
        exitAndConfirmTeardown()
        record.put("teardownConfirmed", true)
        val afterTeardown = JSONObject().put("ordinal", record.getInt("ordinal"))
            .put("afterTeardown", true)
        captures.put(afterTeardown)
        captureAndObserve(record.getInt("ordinal") + 2, afterTeardown)
    }

    private fun exitAndConfirmTeardown() {
        try {
            performTeardown()
        } catch (failure: Throwable) {
            quarantine = true
            report.put("cleanupFailure", failure.toString())
            throw failure
        }
    }

    private fun performTeardown() {
        val manager = p2p
        if (manager == null) {
            if (sdkSlotUnresolved) {
                assertFalse("Transfer start failed before P2P initialization; connection state unknown", groupFormed())
            } else {
                val ack = CountDownLatch(1)
                sdk.glassesControl(byteArrayOf(0x02, 0x01, 0x09)) { _, _ -> ack.countDown() }
                assertTrue("Exit-transfer response absent after failed startup", ack.await(10, TimeUnit.SECONDS))
                assertFalse("Transfer start failed before P2P initialization; connection state unknown", groupFormed())
            }
            transferAttempted = false
            return
        }
        var exitError: String? = if (sdkSlotUnresolved) "SDK response slot unresolved; cannot safely send exit command" else null
        if (exitError == null) {
            try {
                val ack = CountDownLatch(1)
                var code: Int? = null
                sdk.glassesControl(byteArrayOf(0x02, 0x01, 0x09)) { _, rsp -> code = rsp.errorCode; ack.countDown() }
                if (!ack.await(10, TimeUnit.SECONDS)) {
                    sdkSlotUnresolved = true
                    exitError = "Exit-transfer response absent"
                } else if (code != 0) exitError = "Exit-transfer rejected: $code"
            } catch (error: Exception) { exitError = "Exit-transfer threw: $error" }
        }
        manager.stopP2pOperations()
        try {
            val removed = CountDownLatch(1)
            var removeAccepted = false
            manager.removeGroup { ok -> removeAccepted = ok; removed.countDown() }
            val removalResponded = removed.await(8, TimeUnit.SECONDS)
            // A successful removeGroup action is not proof that the group is gone.
            val groupGone = waitUntil(12_000) { !groupFormed() }
            assertTrue("P2P group remained formed (removeAccepted=$removeAccepted callback=$removalResponded)", groupGone)
            assertTrue("Process still bound to a network", (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).boundNetworkForProcess == null)
        } finally {
            p2pCallback?.let { manager.removeCallback(it) }
            p2pCallback = null
            if (p2pRegistered) manager.unregisterReceiver()
            p2pRegistered = false
            p2p = null
            transferAttempted = false
        }
        assertTrue(exitError ?: "Exit-transfer completed", exitError == null)
    }

    private fun groupFormed(): Boolean {
        val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        val channel = manager.initialize(context, Looper.getMainLooper(), null)
        val done = CountDownLatch(2)
        var formed = true // A timeout is not evidence of a clean P2P state.
        var hasGroup = true
        manager.requestConnectionInfo(channel) { info -> formed = info.groupFormed; done.countDown() }
        manager.requestGroupInfo(channel) { group -> hasGroup = group != null; done.countDown() }
        val answered = done.await(3, TimeUnit.SECONDS)
        channel.close()
        check(answered) { "Cannot query P2P group and connection state" }
        return formed || hasGroup
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return true
            Thread.sleep(250)
        }
        return predicate()
    }

    private fun await(timeoutMs: Long, label: String, call: (() -> Unit) -> Unit): Boolean {
        val done = CountDownLatch(1)
        call { done.countDown() }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS).also {
            if (!it) {
                quarantine = true
                sdkSlotUnresolved = true
                report.put("unresolvedSdkSlot", label)
            }
        }
    }

    private fun elapsed(): Long = SystemClock.elapsedRealtime() - clock
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).hex()

    companion object {
        private const val TAG = "HeyCyanImageContract"
        private const val NOTIFY_ID = 7_241
        private const val MAX_CALLBACKS = 2_048
        private const val MAX_THUMBNAIL_BYTES = 4L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val BLE_QUIET_MS = 4_000L
        private const val OPERATOR_WINDOW_MS = 30_000L
    }
}