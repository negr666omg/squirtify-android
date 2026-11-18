package org.squidify.android.downloads

import java.util.UUID

/**
 * Stable, collision-resistant staging name for a download job.
 *
 * The final exported file name is preserved after the unique prefix; the
 * prefix prevents different albums with identically named tracks from
 * sharing a single .part file while two workers run concurrently.
 */
internal fun stagingPartName(jobId: String, fileName: String): String {
    val stableId = UUID.nameUUIDFromBytes(jobId.toByteArray(Charsets.UTF_8))
        .toString()
        .replace("-", "")
    return "$stableId-$fileName.part"
}
