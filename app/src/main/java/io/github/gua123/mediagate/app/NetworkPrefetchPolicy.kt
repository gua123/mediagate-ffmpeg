package io.github.gua123.mediagate.app

import io.github.gua123.mediagate.data.storage.api.PrefetchTargets

/**
 * 公网/弱网下的预取收敛策略（**纯函数，单测覆盖**）。
 *
 * 起因（2026-11-03 真机）：公网 SFTP 实测只有 ~70 KB/s 时，进入一个目录会把前 12 个视频
 * 各预热「头 2 段 + 尾 1 段」＝12 MB，预取任务挂在应用级作用域、退出播放也不停；
 * 而 SFTP 通道池只有 4 条 ⇒ 播放要的段、列目录/取属性都得排队，表现就是「一直转圈」。
 *
 * 原则：**公网只做最小必要预取，把带宽和通道留给播放与元数据（列目录 / 取属性）**；
 * 局域网保持原有策略（打开文件夹预热、预读 4 段、滑窗并行等）。
 */
object NetworkPrefetchPolicy {

    /** 「打开文件夹自动预热」只在局域网做；公网直接关掉（12 视频 × 12 MB 会堵死链路）。 */
    fun folderWarmUpEnabled(isWan: Boolean): Boolean = !isWan

    /** 读到某段后顺手预读几段：公网压到 1 段，局域网尊重用户设置。 */
    fun readAheadSegments(isWan: Boolean, configured: Int): Int =
        if (isWan) configured.coerceIn(0, 1) else configured

    /**
     * 打开一个文件时该预取哪些区间。
     *
     * 局域网沿用 [PrefetchTargets.openPlan]（首段 + 尾段 + 续播落点，大文件多抓两段）；
     * 公网只留「起播点（无续播就是首段）」＋「尾段（moov / cues 常在末尾）」两段，避免一次占满带宽与通道。
     */
    fun openChunks(
        isWan: Boolean,
        size: Long?,
        segmentBytes: Long,
        positionMs: Long,
        durationMs: Long,
    ): List<PrefetchTargets.Chunk> {
        val planned = PrefetchTargets.openPlan(size, segmentBytes, positionMs, durationMs)
        if (!isWan) return planned
        return wanOpenChunks(size, segmentBytes, positionMs, durationMs)
    }

    /** 打开文件时的几个预取区间是否并发发出（公网顺序发，避免一次占满通道）。 */
    fun openPrefetchParallel(isWan: Boolean): Boolean = !isWan

    /** 滑窗预取一次抓几段：公网 1 段（自适应结果再往下收）。 */
    fun slidingWindowSegments(isWan: Boolean, adaptive: Int): Int =
        if (isWan) adaptive.coerceIn(0, 1) else adaptive

    /** 滑窗预取并发：公网 1 个请求。 */
    fun slidingWindowConcurrency(isWan: Boolean): Int = if (isWan) 1 else 2

    /** 下一集预热：公网不做（否则会额外抓下一个文件的头尾）。 */
    fun nextEpisodeWarmEnabled(isWan: Boolean): Boolean = !isWan

    /** 拖拽落点预取并发：公网 1 个请求。 */
    fun seekPrefetchConcurrency(isWan: Boolean): Int = if (isWan) 1 else 2

    /**
     * 公网的最小预取计划：起播点（有续播位置就用续播点，否则首段）+ 尾段。
     *
     * 任何参数不可用都返回空（宁可不动，也不瞎猜偏移——与 [PrefetchTargets] 同一口径）。
     */
    private fun wanOpenChunks(
        size: Long?,
        segmentBytes: Long,
        positionMs: Long,
        durationMs: Long,
    ): List<PrefetchTargets.Chunk> {
        val total = size ?: return emptyList()
        if (total <= 0L || segmentBytes <= 0L) return emptyList()
        val chunks = mutableListOf<PrefetchTargets.Chunk>()
        val start = PrefetchTargets.offsetForPosition(positionMs, durationMs, total)
            ?.takeIf { it > 0L }
            ?: 0L
        chunks += PrefetchTargets.Chunk(start, segmentBytes.coerceAtMost(total - start))
        PrefetchTargets.tailOffset(total, segmentBytes, segmentBytes)?.let { offset ->
            val length = segmentBytes.coerceAtMost(total - offset)
            if (length > 0L) chunks += PrefetchTargets.Chunk(offset, length)
        }
        return chunks.distinctBy { it.offset }
    }
}
