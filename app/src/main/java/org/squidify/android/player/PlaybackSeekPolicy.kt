package org.squidify.android.player

/**
 * A transcoded network stream may claim to be seekable depending on server
 * headers and extractor state even though seeking inside the response body is
 * not reliable. Only a positively complete cache entry makes native seeking
 * safe for transcoded content.
 */
internal fun shouldUseNativeSeek(
    isTranscoded: Boolean,
    isSeekable: Boolean,
    durationTrustworthy: Boolean,
    isFullyCached: Boolean,
): Boolean = isSeekable && durationTrustworthy && (!isTranscoded || isFullyCached)

/** A saved KHInsider track can play locally without resolving an online URL. */
internal fun shouldResolveKhiTrack(hasDirectUrl: Boolean, hasLocalFile: Boolean): Boolean =
    !hasDirectUrl && !hasLocalFile
