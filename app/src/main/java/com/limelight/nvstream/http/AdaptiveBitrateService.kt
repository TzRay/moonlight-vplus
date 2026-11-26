/*
 * Moonlight for Android - Adaptive Bitrate Service
 *
 * 智能码率调节，移植自 HarmonyOS 版本：
 *   1. 优先让 Sunshine 服务端做码率决策（ABR API feedback 模式）
 *   2. 服务端不支持时回退到客户端本地控制器（PID 风格）
 *
 * 客户端每秒上报网络指标 → 服务端返回码率调整指令 → 客户端执行
 */
package com.limelight.nvstream.http

import com.limelight.LimeLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AdaptiveBitrateService(
    private val nvHttpFactory: () -> AbrEndpoint,
    private val statsProvider: () -> AbrStats?,
    /** 码率成功调整后的回调（已在 service 线程发到服务端）。仅用于更新本地 prefConfig / UI。*/
    private val onBitrateChanged: (bitrateKbps: Int, reason: String) -> Unit,
    private val resetSampler: () -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AdaptiveBitrateService").apply { isDaemon = true }
    },
    private val shutdownExecutorOnStop: Boolean = true
) {
    data class AbrStats(
        val packetLoss: Float,
        val rttMs: Int,
        val decodeFps: Float,
        val droppedFrames: Int,
        val packets: AbrPacketSample
    )

    internal class StatsSampler {
        private data class Sample(val packets: Long, val decoded: Int, val delivered: Int, val lostFrames: Long,
                                  val gaps: Long, val timeMs: Long, val generation: Int)
        private var previous: Sample? = null
        private var newWindow = true
        fun reset() { previous = null; newWindow = true }

        fun sample(packets: Long, decoded: Int, lostFrames: Long, timeMs: Long, rttInfo: Long,
                   gaps: Long, delivered: Int, generation: Int): AbrStats? {
            val current = Sample(packets, decoded, delivered, lostFrames, gaps, timeMs, generation)
            val last = previous
            previous = current
            if (last == null || generation != last.generation || decoded < last.decoded ||
                delivered < last.delivered || lostFrames < last.lostFrames || timeMs <= last.timeMs) {
                newWindow = true
                return null
            }
            val expected = ((packets and UINT_MASK) - (last.packets and UINT_MASK)) and UINT_MASK
            val missing = ((packets ushr 32) - (last.packets ushr 32)) and UINT_MASK
            val coverage = (gaps - last.gaps) and UINT_MASK
            if (missing > expected) { newWindow = true; return null }
            val observation = AbrPacketSample(expected, missing, coverage, delivered - last.delivered,
                expected >= 100 && coverage == 0L, newWindow)
            newWindow = false
            return AbrStats(
                if (expected > 0) missing * 100f / expected else 0f,
                (rttInfo ushr 32).toInt().coerceAtLeast(0),
                (decoded - last.decoded) * 1000f / (timeMs - last.timeMs),
                (lostFrames - last.lostFrames).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                observation
            )
        }
        private companion object { const val UINT_MASK = 0xffffffffL }
    }

    private data class ObservedTick(val ms: Long, val stats: AbrStats)
    private val history = java.util.ArrayDeque<ObservedTick>()
    private val revision = java.util.concurrent.atomic.AtomicLong()
    private val resetSampling = java.util.concurrent.atomic.AtomicBoolean(true)
    @Volatile private var suspended = false
    private var packetFeedback = false
    private var future: ScheduledFuture<*>? = null

    @Volatile private var nvHttp: AbrEndpoint? = null
    @Volatile var enabled: Boolean = false
        private set
    @Volatile var serverSupported: Boolean = false
        private set
    @Volatile var currentBitrate: Int = 0
        private set

    /** UI 可订阅码率变更事件（在 service 线程上回调，召者需自行 post 到主线程）。*/
    @Volatile var bitrateListener: ((bitrateKbps: Int, reason: String) -> Unit)? = null

    private var initialBitrate: Int = 0
    private var mode: String = MODE_BALANCED
    private var minBitrate: Int = 3000
    private var maxBitrate: Int = 100_000

    // 本地 fallback 控制器状态
    private var stableSeconds = 0
    private var lossStreak = 0
    private var lastAdjustWallClock = 0L
    private var lastDirection = DIR_NONE

    // 服务端启用重试
    private var serverEnableRetries = 0
    private var serverRetryTickCounter = 0

    /**
     * 启动 ABR。会在后台线程探测服务端能力。
     * @param initialBitrate 当前码率（kbps），ABR 围绕此值上下浮动
     */
    fun start(initialBitrate: Int, mode: String) {
        if (enabled) return
        this.initialBitrate = initialBitrate
        this.currentBitrate = initialBitrate
        this.mode = mode
        applyModePreset(mode)
        resetState()
        enabled = true

        executor.execute {
            try {
                val http = nvHttpFactory()
                nvHttp = http
                val caps = http.getAbrCapabilities()
                packetFeedback = "packet_sample_v2" in caps.features
                serverSupported = caps.supported
                if (caps.supported) serverEnableRetries = 1
                LimeLog.info("[ABR] 启动: bitrate=${initialBitrate}kbps, mode=$mode, server=${caps.supported} (v${caps.version})")
            } catch (e: Exception) {
                LimeLog.warning("[ABR] 服务端能力探测失败: ${e.message}")
            }
        }

        // 使用 scheduleWithFixedDelay 而非 scheduleAtFixedRate：
        // Android 进程被 cached 后唤醒时，fixedRate 会"补跑"积压的几百上千次 tick，
        // 而 fixedDelay 只在每次执行完成后再等待 1 秒，避免突发风暴。
        future = executor.scheduleWithFixedDelay({
            try {
                tick()
            } catch (e: Exception) {
                LimeLog.warning("[ABR] tick 异常: ${e.message}")
            }
        }, START_DELAY_SECONDS, 1, TimeUnit.SECONDS)
    }

    fun setSuspended(value: Boolean) {
        if (!enabled || suspended == value) return
        suspended = value
        revision.incrementAndGet()
        resetSampling.set(true)
    }

    // Every user bitrate change uses the same executor as feedback and its
    // resulting HTTP write. Invalidate the in-flight recommendation immediately.
    @Synchronized fun setManualBitrate(kbps: Int, finished: (Boolean) -> Unit): Boolean {
        if (!enabled) return false
        revision.incrementAndGet()
        executor.execute {
            val ok = applyBitrateInternal(kbps, "manual")
            if (ok) {
                lastAdjustWallClock = nowMs()
                resetControllerRange()
            }
            finished(ok)
        }
        return true
    }

    @Synchronized fun setMode(newMode: String) {
        if (!enabled) return
        revision.incrementAndGet()
        executor.execute {
            mode = newMode
            resetControllerRange()
        }
    }

    private fun resetControllerRange() {
        applyModePreset(mode, currentBitrate)
        if (serverSupported) {
            serverEnableRetries = 1
            serverRetryTickCounter = 0
        }
        resetSampling.set(true)
    }

    private fun current(ticket: Long) = enabled && !suspended && revision.get() == ticket

    @Synchronized fun stop() {
        if (!enabled) return
        enabled = false
        revision.incrementAndGet()
        future?.cancel(false)
        future = null

        // 通知服务端关闭并恢复初始码率
        executor.execute {
            try {
                if (serverSupported) {
                    nvHttp?.setAbrMode(AbrConfig(false, 0, 0, MODE_BALANCED))
                }
                if (currentBitrate != initialBitrate) {
                    applyBitrateInternal(initialBitrate, "restore")
                }
            } catch (e: Exception) {
                LimeLog.warning("[ABR] stop 异常: ${e.message}")
            }
            LimeLog.info("[ABR] 停止，恢复码率: ${initialBitrate}kbps")
        }
        if (shutdownExecutorOnStop) executor.shutdown()
    }

    /** 用于性能面板显示当前 ABR 状态。*/
    fun getStatusText(): String {
        if (!enabled) return ""
        val sub = when {
            serverSupported && serverEnableRetries == 0 -> "server"
            serverSupported && serverEnableRetries > 0 -> "connecting"
            else -> "local"
        }
        return "ABR:$sub ${currentBitrate / 1000}M"
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    private fun applyModePreset(mode: String, baseline: Int = initialBitrate) {
        val range = bitrateRange(baseline, mode)
        minBitrate = range.first
        maxBitrate = range.last
    }

    private fun resetState() {
        stableSeconds = 0
        lossStreak = 0
        lastAdjustWallClock = 0L
        lastDirection = DIR_NONE
        serverEnableRetries = 0
        serverRetryTickCounter = 0
    }

    internal fun tick() {
        val ticket = revision.get()
        if (!current(ticket)) return
        val http = nvHttp ?: return
        if (resetSampling.getAndSet(false)) {
            resetSampler()
            history.clear()
            stableSeconds = 0
            lossStreak = 0
        }
        val stats = statsProvider() ?: return

        // 服务端启用惰性重试
        if (serverSupported && serverEnableRetries > 0) {
            if (++serverRetryTickCounter >= SERVER_RETRY_INTERVAL_TICKS || serverEnableRetries == 1 && serverRetryTickCounter == 1) {
                serverRetryTickCounter = 0
                val ok = http.setAbrMode(AbrConfig(true, minBitrate, maxBitrate, mode))
                if (ok) {
                    LimeLog.info("[ABR] 服务端 ABR 启用成功（第 $serverEnableRetries 次）")
                    serverEnableRetries = 0
                } else if (++serverEnableRetries > MAX_SERVER_ENABLE_RETRIES) {
                    serverSupported = false
                    LimeLog.warning("[ABR] 服务端重试 $MAX_SERVER_ENABLE_RETRIES 次失败，降级到本地控制器")
                }
            }
        }

        if (serverSupported && serverEnableRetries == 0) {
            tickServer(http, stats, ticket)
        } else {
            tickLocal(stats, ticket)
        }
    }

    private fun tickServer(http: AbrEndpoint, stats: AbrStats, ticket: Long) {
        if (!current(ticket)) return
        if (!packetFeedback && !stats.packets.trusted) {
            // The legacy feedback format cannot distinguish an unknown packet
            // denominator from clean delivery. Locally confirmed frame damage
            // can still lower the rate without submitting a misleading report.
            tickLocal(stats, ticket)
            return
        }
        val feedback = NetworkFeedback(
            packetLoss = stats.packetLoss,
            rttMs = stats.rttMs,
            decodeFps = stats.decodeFps,
            droppedFrames = stats.droppedFrames,
            currentBitrate = currentBitrate,
            packets = stats.packets.takeIf { packetFeedback }
        )
        val action = http.reportNetworkFeedback(feedback)
        if (!current(ticket)) {
            // Legacy hosts apply before replying. A lost response can hide an
            // applied change just as a late positive response can.
            val restore = if (enabled) currentBitrate else initialBitrate
            if (!packetFeedback && (action == null || action.newBitrate?.let { it != restore } == true)) {
                http.setBitrate(restore)
            }
            return
        }
        val newBitrate = action?.newBitrate ?: return
        if (newBitrate == currentBitrate) return
        val clamped = newBitrate.coerceIn(minBitrate, maxBitrate)
        applyBitrateInternal(clamped, action.reason ?: "server", source = "server", ticket = ticket)
    }

    /** 内部统一码率应用：复用缓存的 nvHttp 实例，避免每次新建 OkHttpClient + TLS。*/
    private fun applyBitrateInternal(kbps: Int, reason: String, source: String = "local", ticket: Long? = null): Boolean {
        if (ticket != null && !current(ticket)) return false
        val http = nvHttp ?: return false
        val from = currentBitrate
        return try {
            if (http.setBitrate(kbps)) {
                if (ticket != null && !current(ticket)) {
                    http.setBitrate(if (enabled) currentBitrate else initialBitrate)
                    return false
                }
                currentBitrate = kbps
                LimeLog.info("[ABR][$source] ${from}kbps -> ${kbps}kbps ($reason)")
                onBitrateChanged(kbps, reason)
                try { bitrateListener?.invoke(kbps, reason) } catch (_: Exception) {}
                true
            } else false
        } catch (e: Exception) {
            LimeLog.warning("[ABR] setBitrate 失败: ${e.message}")
            false
        }
    }

    private fun tickLocal(stats: AbrStats, ticket: Long) {
        val now = nowMs()
        if (stats.packets.resetWindow) {
            history.clear()
            stableSeconds = 0
            lossStreak = 0
        }
        while (history.peekFirst()?.let { now - it.ms > 4000 } == true) history.removeFirst()
        history.addLast(ObservedTick(now, stats))
        var affected = 0
        var missing = false
        var incomplete = false
        var risingRtt = false
        var previousRtt = 0
        for ((_, tick) in history) {
            if (tick.droppedFrames > 0) affected++
            missing = missing || tick.packets.missing > 0
            incomplete = incomplete || tick.packets.coverageGaps > 0
            risingRtt = risingRtt || previousRtt > 0 && tick.rttMs >= previousRtt + 15 && tick.rttMs >= previousRtt * 1.5
            if (tick.rttMs > 0) previousRtt = tick.rttMs
        }
        val damage = stats.droppedFrames > 0 && (incomplete || stats.droppedFrames >= 3 ||
            affected >= 3 || affected >= 2 && (missing || risingRtt))
        if (!stats.packets.trusted || stats.droppedFrames > 0) stableSeconds = 0
        if (!stats.packets.trusted && !damage) {
            lossStreak = 0
            return
        }
        val cooldown = if (mode == MODE_LOW_LATENCY) 1500 else 2000
        if (now - lastAdjustWallClock < cooldown) return

        var newBitrate = currentBitrate.toDouble()
        var reason = ""

        when {
            stats.packets.trusted && stats.packetLoss > 5f -> {
                newBitrate = currentBitrate * 0.7
                reason = "loss=%.1f%% emergency".format(stats.packetLoss)
                stableSeconds = 0
                lossStreak++
            }
            damage -> {
                newBitrate = currentBitrate * 0.9
                reason = "delivery_loss"
                lossStreak = 0
            }
            stats.packetLoss > 2f -> {
                lossStreak++
                if (lossStreak >= 2) {
                    newBitrate = currentBitrate * 0.9
                    reason = "loss=%.1f%% sustained".format(stats.packetLoss)
                    stableSeconds = 0
                }
            }
            stats.packetLoss > 0.5f -> {
                lossStreak++
                stableSeconds = 0
                if (lossStreak >= 4) {
                    newBitrate = currentBitrate * 0.95
                    reason = "loss=%.1f%% mild".format(stats.packetLoss)
                }
            }
            else -> {
                lossStreak = 0
                if (stats.droppedFrames > 0) return
                stableSeconds++
                val probeThreshold = if (mode == MODE_QUALITY) 3 else 5
                if (stableSeconds >= probeThreshold && currentBitrate < maxBitrate) {
                    val step = if (lastDirection == DIR_DOWN) 1.02 else 1.05
                    newBitrate = currentBitrate * step
                    reason = "stable ${stableSeconds}s probe"
                    stableSeconds = 0
                }
            }
        }

        val target = newBitrate.toInt().coerceIn(minBitrate, maxBitrate)
        if (target != currentBitrate) {
            val direction = if (target > currentBitrate) DIR_UP else DIR_DOWN
            if (applyBitrateInternal(target, reason, source = "local", ticket = ticket)) {
                lastDirection = direction
                lastAdjustWallClock = now
            }
        }
    }

    companion object {
        const val MODE_QUALITY = "quality"
        const val MODE_BALANCED = "balanced"
        const val MODE_LOW_LATENCY = "lowLatency"

        /** Preset bounds must remain valid and contain the selected starting bitrate. */
        fun bitrateRange(initialBitrate: Int, mode: String): IntRange {
            val baseline = initialBitrate.coerceIn(500, 800_000)
            val preset = when (mode) {
                MODE_QUALITY -> maxOf(5000, baseline / 2) to minOf(150_000, (baseline * 1.5).toInt())
                MODE_LOW_LATENCY -> 2000 to (baseline * 1.2).toInt()
                else -> maxOf(3000, (baseline * 0.3).toInt()) to minOf(150_000, baseline * 2)
            }
            return minOf(baseline, preset.first).coerceAtLeast(500)..
                maxOf(baseline, preset.second).coerceAtMost(800_000)
        }

        private const val START_DELAY_SECONDS = 3L
        private const val SERVER_RETRY_INTERVAL_TICKS = 5
        private const val MAX_SERVER_ENABLE_RETRIES = 10

        private const val DIR_NONE = 0
        private const val DIR_UP = 1
        private const val DIR_DOWN = -1
    }
}
