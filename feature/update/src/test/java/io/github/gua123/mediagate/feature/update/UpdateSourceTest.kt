package io.github.gua123.mediagate.feature.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新源按仓库生成（2026-11-02：自研 FFmpeg 测试版独立仓库）。
 *
 * 为什么钉住 URL：测试版的清单/资产/发布页必须各读各的仓库——这里写错一个字母，
 * 应用内更新就会去正式版仓库比对版本号（真机上表现为"永远已是最新"或版本串线）。
 */
class UpdateSourceTest {

    @Test
    fun `forRepo 生成测试版自己的三条清单与发布页`() {
        val source = UpdateSource.forRepo("gua123", "mediagate-ffmpeg")
        assertEquals(
            "https://raw.githubusercontent.com/gua123/mediagate-ffmpeg/main/update.json",
            source.manifestUrl,
        )
        assertEquals(
            "https://raw.githubusercontent.com/gua123/mediagate-ffmpeg/refs/heads/main/update.json",
            source.mirrorManifestUrl,
        )
        assertEquals(
            "https://cdn.jsdelivr.net/gh/gua123/mediagate-ffmpeg@main/update.json",
            source.cdnManifestUrl,
        )
        assertEquals(
            "https://github.com/gua123/mediagate-ffmpeg/releases",
            source.releasesPageUrl,
        )
    }

    @Test
    fun `默认更新源仍指向正式版仓库`() {
        val source = UpdateSource()
        assertEquals(
            "https://raw.githubusercontent.com/gua123/mediagate/main/update.json",
            source.manifestUrl,
        )
        assertEquals(UpdateSource.RELEASES_PAGE, source.releasesPageUrl)
    }
}
