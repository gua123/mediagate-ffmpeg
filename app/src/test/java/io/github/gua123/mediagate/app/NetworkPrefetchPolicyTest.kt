package io.github.gua123.mediagate.app

import io.github.gua123.mediagate.data.storage.api.PrefetchTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公网/弱网预取收敛策略（2026-11-03 公网转圈根因排查后加）。
 *
 * 这些断言钉住「公网不再把整目录/大文件一次抓满」：改坏了会重新把带宽和 SFTP 通道占满。
 */
class NetworkPrefetchPolicyTest {

    private val seg = 4L * 1024 * 1024

    @Test
    fun `打开文件夹预热只在局域网开启`() {
        assertTrue(NetworkPrefetchPolicy.folderWarmUpEnabled(isWan = false))
        assertFalse(NetworkPrefetchPolicy.folderWarmUpEnabled(isWan = true))
    }

    @Test
    fun `公网预读段数压到 1 且尊重关闭档`() {
        assertEquals(1, NetworkPrefetchPolicy.readAheadSegments(isWan = true, configured = 4))
        assertEquals(0, NetworkPrefetchPolicy.readAheadSegments(isWan = true, configured = 0))
        assertEquals(4, NetworkPrefetchPolicy.readAheadSegments(isWan = false, configured = 4))
    }

    @Test
    fun `公网打开文件只预取起播点与尾段`() {
        val size = 100L * seg
        val chunks = NetworkPrefetchPolicy.openChunks(
            isWan = true,
            size = size,
            segmentBytes = seg,
            positionMs = 0L,
            durationMs = 0L,
        )
        assertEquals(2, chunks.size)
        assertEquals(0L, chunks[0].offset)
        assertEquals(size - seg, chunks[1].offset)
    }

    @Test
    fun `公网续播只预取续播点而不是开头`() {
        val size = 100L * seg
        val chunks = NetworkPrefetchPolicy.openChunks(
            isWan = true,
            size = size,
            segmentBytes = seg,
            positionMs = 50_000L,
            durationMs = 100_000L,
        )
        assertTrue(chunks.first().offset > 0L)
        assertEquals(size - seg, chunks.last().offset)
    }

    @Test
    fun `局域网保持原计划`() {
        val size = 2L * 1024 * 1024 * 1024 // 大文件
        val chunks = NetworkPrefetchPolicy.openChunks(
            isWan = false,
            size = size,
            segmentBytes = seg,
            positionMs = 0L,
            durationMs = 0L,
        )
        assertEquals(PrefetchTargets.openPlan(size, seg, 0L, 0L), chunks)
    }

    @Test
    fun `公网不并发预取也不预热下一集`() {
        assertFalse(NetworkPrefetchPolicy.openPrefetchParallel(isWan = true))
        assertFalse(NetworkPrefetchPolicy.nextEpisodeWarmEnabled(isWan = true))
        assertEquals(1, NetworkPrefetchPolicy.slidingWindowConcurrency(isWan = true))
        assertEquals(1, NetworkPrefetchPolicy.seekPrefetchConcurrency(isWan = true))
        assertEquals(1, NetworkPrefetchPolicy.slidingWindowSegments(isWan = true, adaptive = 4))
    }
}
