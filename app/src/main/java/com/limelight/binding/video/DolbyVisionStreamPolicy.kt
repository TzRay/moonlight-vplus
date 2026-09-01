package com.limelight.binding.video

import android.annotation.SuppressLint
import android.media.MediaCodecInfo

/**
 * Dolby Vision Profile 8.1 的流级约束。
 *
 * Level 必须按解码像素率选择，不能使用 HEVC level 或任意硬编码值代替。
 */
internal object DolbyVisionStreamPolicy {
    private const val RPU_NAL_UNIT_TYPE = 62

    private data class LevelLimit(
        val maxPixelRate: Long,
        val maxWidth: Int,
        val codecLevel: Int,
    )

    @SuppressLint("InlinedApi")
    private val levelLimits = listOf(
        LevelLimit(22_118_400L, 1280, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelHd24),
        LevelLimit(27_648_000L, 1280, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelHd30),
        LevelLimit(49_766_400L, 1920, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd24),
        LevelLimit(62_208_000L, 1920, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd30),
        LevelLimit(124_416_000L, 1920, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd60),
        LevelLimit(199_065_600L, 3840, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd24),
        LevelLimit(248_832_000L, 3840, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd30),
        LevelLimit(398_131_200L, 3840, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd48),
        LevelLimit(497_664_000L, 3840, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd60),
    )

    /** 返回能够覆盖当前分辨率和帧率的最小 Dolby Vision level。 */
    fun levelFor(width: Int, height: Int, frameRate: Int): Int? {
        if (width <= 0 || height <= 0 || frameRate <= 0) return null

        val pixelRate = width.toLong() * height.toLong() * frameRate.toLong()
        return levelLimits.firstOrNull { width <= it.maxWidth && pixelRate <= it.maxPixelRate }
            ?.codecLevel
    }

    /** Profile 8.1 的首个随机访问帧必须包含有效 RPU NAL。 */
    fun containsRpuNalUnit(data: ByteArray, length: Int): Boolean {
        val limit = length.coerceAtMost(data.size)
        var searchOffset = 0
        while (searchOffset < limit) {
            val startCode = findStartCode(data, searchOffset, limit) ?: return false
            val nalHeaderOffset = startCode.first + startCode.second
            if (nalHeaderOffset < limit) {
                val nalType = (data[nalHeaderOffset].toInt() ushr 1) and 0x3f
                if (nalType == RPU_NAL_UNIT_TYPE) return true
            }
            searchOffset = nalHeaderOffset + 1
        }
        return false
    }

    private fun findStartCode(data: ByteArray, offset: Int, limit: Int): Pair<Int, Int>? {
        var index = offset
        while (index + 3 < limit) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) {
                if (data[index + 2] == 1.toByte()) return index to 3
                if (index + 4 < limit && data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte()) {
                    return index to 4
                }
            }
            index++
        }
        return null
    }
}
