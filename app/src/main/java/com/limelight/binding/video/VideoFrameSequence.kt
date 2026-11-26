package com.limelight.binding.video

import java.util.concurrent.atomic.AtomicInteger

/** The UI and codec recovery paths publish resets. Only the submission
 * thread consumes them and updates the previous accepted frame number. */
internal class VideoFrameSequence {
    private val resetGeneration = AtomicInteger()
    val generation: Int get() = resetGeneration.get()
    private var acceptedGeneration = 0
    private var lastFrameNumber = 0

    fun reset() { resetGeneration.incrementAndGet() }

    fun accept(frameNumber: Int): Int {
        val current = generation
        val previous = if (current == acceptedGeneration) lastFrameNumber else 0
        lastFrameNumber = frameNumber
        acceptedGeneration = current
        return previous
    }
}
