package com.fersaiyan.cyanbridge.media

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

/** Owns the one device-wide photo-ready notification until its capture transaction ends. */
internal class HeyCyanPhotoReadyClaim {
    private val pending = AtomicReference<CompletableDeferred<Unit>?>(null)

    fun acquire(): CompletableDeferred<Unit> {
        val claim = CompletableDeferred<Unit>()
        check(pending.compareAndSet(null, claim)) {
            "A HeyCyan capture is already awaiting a notification"
        }
        return claim
    }

    fun current(): CompletableDeferred<Unit>? = pending.get()

    fun release(claim: CompletableDeferred<Unit>) {
        pending.compareAndSet(claim, null)
    }
}