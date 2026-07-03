package com.example.charucocalibrator

import java.util.LinkedHashMap
import kotlin.math.abs

/**
 * Bounded LRU of [FrameMetadata] keyed by [FrameMetadata.sensorTimestampNs].
 * Lookup prefers an exact timestamp match, then the nearest prior capture result
 * within [MAX_TIMESTAMP_DELTA_NS] (one 30 fps frame).
 */
class CaptureMetadataStore(
    private val maxEntries: Int = MAX_METADATA_ENTRIES
) {
    private val lock = Any()
    private val entries = LinkedHashMap<Long, FrameMetadata>()

    fun store(metadata: FrameMetadata) {
        val timestamp = metadata.sensorTimestampNs ?: return
        synchronized(lock) {
            entries[timestamp] = metadata
            trim()
        }
    }

    fun lookup(timestampNs: Long?, consume: Boolean = false): FrameMetadata? {
        if (timestampNs == null) return null
        synchronized(lock) {
            entries[timestampNs]?.let { metadata ->
                if (consume) entries.remove(timestampNs)
                return metadata
            }

            val nearestPrior = entries.entries
                .filter { (key, _) -> key <= timestampNs && abs(key - timestampNs) <= MAX_TIMESTAMP_DELTA_NS }
                .maxByOrNull { it.key }
                ?.value
            if (nearestPrior != null && consume) {
                nearestPrior.sensorTimestampNs?.let(entries::remove)
            }
            return nearestPrior
        }
    }

    fun latest(): FrameMetadata? = synchronized(lock) {
        entries.entries.maxByOrNull { it.key }?.value
    }

    fun clear() = synchronized(lock) { entries.clear() }

    private fun trim() {
        while (entries.size > maxEntries) {
            entries.entries.iterator().run {
                next()
                remove()
            }
        }
    }

    companion object {
        const val MAX_METADATA_ENTRIES = 64
        const val MAX_TIMESTAMP_DELTA_NS = 33_000_000L
        const val METADATA_WAIT_ATTEMPTS = 20
        const val METADATA_WAIT_MILLIS = 10L
    }
}
