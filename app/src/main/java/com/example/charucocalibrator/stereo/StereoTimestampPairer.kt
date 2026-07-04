package com.example.charucocalibrator.stereo

class StereoTimestampPairer<T>(
    private val timestampNs: (T) -> Long,
    private val maximumDeltaNs: Long = StereoTimestampUtils.REJECT_SAVE_TIMESTAMP_DELTA_NS,
    private val maximumQueuedFrames: Int = 4
) {
    private val leftFrames = ArrayDeque<T>()
    private val rightFrames = ArrayDeque<T>()

    @Synchronized
    fun clear() {
        leftFrames.clear()
        rightFrames.clear()
    }

    @Synchronized
    fun offerLeft(frame: T): Pair<T, T>? = offer(frame, isLeft = true)

    @Synchronized
    fun offerRight(frame: T): Pair<T, T>? = offer(frame, isLeft = false)

    private fun offer(frame: T, isLeft: Boolean): Pair<T, T>? {
        val ownFrames = if (isLeft) leftFrames else rightFrames
        val otherFrames = if (isLeft) rightFrames else leftFrames
        ownFrames.addLast(frame)
        while (ownFrames.size > maximumQueuedFrames) ownFrames.removeFirst()
        while (otherFrames.size > maximumQueuedFrames) otherFrames.removeFirst()

        val closest = otherFrames.minByOrNull { other ->
            StereoTimestampUtils.deltaNs(timestampNs(frame), timestampNs(other))
        } ?: return null
        val delta = StereoTimestampUtils.deltaNs(timestampNs(frame), timestampNs(closest))
        if (delta > maximumDeltaNs) return null

        ownFrames.remove(frame)
        otherFrames.remove(closest)
        return if (isLeft) frame to closest else closest to frame
    }
}
