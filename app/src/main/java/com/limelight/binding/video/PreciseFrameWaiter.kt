package com.limelight.binding.video

import java.util.concurrent.locks.LockSupport

/** Keeps early/spurious park returns from turning a whole frame into a busy wait. */
internal class PreciseFrameWaiter(
    private val isActive: ActiveCheck,
    private val nanoTime: NanoClock = NanoClock(System::nanoTime),
    private val parkNanos: Parker = Parker(LockSupport::parkNanos),
) {
    // Primitive SAM methods avoid boxing a Long on every spin-loop clock read.
    fun interface ActiveCheck { fun read(): Boolean }
    fun interface NanoClock { fun read(): Long }
    fun interface Parker { fun park(durationNs: Long) }

    fun await(deadlineNs: Long) {
        while (isActive.read() && !Thread.currentThread().isInterrupted) {
            val remainingNs = deadlineNs - nanoTime.read()
            if (remainingNs <= 0L) return
            if (remainingNs > SPIN_WINDOW_NS) {
                parkNanos.park(remainingNs - SPIN_WINDOW_NS)
                // parkNanos may return early for any reason. Recheck both the
                // deadline and cancellation before entering the spin window.
            }
        }
    }

    companion object {
        // Preserve the existing precision budget; tuning it is a separate experiment.
        const val SPIN_WINDOW_NS = 500_000L
    }
}
