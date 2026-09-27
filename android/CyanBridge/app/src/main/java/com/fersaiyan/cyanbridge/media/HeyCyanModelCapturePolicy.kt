package com.fersaiyan.cyanbridge.media

/** Selects exactly one post-capture JPEG without relying on filename ordering. */
internal object HeyCyanModelCapturePolicy {
    fun selectCapturedPhoto(
        preCaptureSnapshot: Set<HeyCyanMediaManifestItem>,
        postCaptureItems: Collection<HeyCyanMediaManifestItem>,
    ): Result<HeyCyanMediaManifestItem> = runCatching {
        val newPhotos = postCaptureItems
            .asSequence()
            .filter { it.type == HeyCyanMediaType.PHOTO && it !in preCaptureSnapshot }
            .toList()
        require(newPhotos.size == 1) {
            "Expected exactly one new JPEG after capture, found ${newPhotos.size}"
        }
        newPhotos.single()
    }
}