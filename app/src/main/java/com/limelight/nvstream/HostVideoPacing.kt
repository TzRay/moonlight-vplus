package com.limelight.nvstream

/** Versioned host pacing negotiation; never change a legacy host's defaults. */
internal object HostVideoPacing {
    const val SMOOTH_V1 = "smooth-v1"

    fun request(enabled: Boolean, nvidiaHost: Boolean, advertisedModes: String?): String? {
        if (!enabled || nvidiaHost) return null
        return if (advertisedModes.orEmpty().split(',', ' ', '\t', '\n').any { it == SMOOTH_V1 }) {
            SMOOTH_V1
        } else null
    }

    fun acknowledged(requested: String?, response: String?): Boolean =
        requested == SMOOTH_V1 && response == SMOOTH_V1
}
