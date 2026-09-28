package com.fersaiyan.cyanbridge.media

/** Selects exactly one post-capture JPEG without relying on filename ordering. */
internal object HeyCyanModelCapturePolicy {
    fun selectCapturedPhoto(
        preCaptureSnapshot: Set<HeyCyanMediaManifestItem>?,
        baselinePhotoCount: Int?,
        postCaptureItems: Collection<HeyCyanMediaManifestItem>,
    ): Result<HeyCyanMediaManifestItem> = runCatching {
        if (preCaptureSnapshot == null) {
            val postCapturePhotos = postCaptureItems.filter { it.type == HeyCyanMediaType.PHOTO }
            require(baselinePhotoCount == 0 && postCapturePhotos.size == 1) {
                "No pre-capture media snapshot is available to identify the new JPEG"
            }
            return@runCatching postCapturePhotos.single()
        }
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