# Android 硬解播放引擎调研（2026-11-05）

> 目的：给 mediagate-ffmpeg 找一条「开放、可改、能硬解」的播放引擎路线。
> 结论先行：**不一定要换引擎**——本项目的自研 FFmpeg 构建已经开了 MediaCodec（--enable-jni --enable-mediacodec），
> 缺的是「用 mediacodec 解码器 + Surface 输出」这一段；如果换引擎，按「集成成本 / 许可证 / 硬解」排序见下表。

## 候选对比

| 引擎 | 仓库 | 许可证 | Android 硬解 | 形态 | 活跃度 | 适合本项目吗 |
|---|---|---|---|---|---|---|
| **Media3 / ExoPlayer** | androidx/media | Apache-2.0 | MediaCodec | 官方 AAR（Gradle） | 非常活跃（2026-11） | ★★★★★ 正式版已在用，接入成本最低；格式覆盖不如 FFmpeg，硬解优先 |
| **自研 FFmpeg + MediaCodec** | 本项目 :media:engine-native | FFmpeg LGPL/GPL（按构建） | 需接 h264_mediacodec/hevc_mediacodec + Surface | 已有 native 引擎 | 自己维护 | ★★★★ 保留自研与格式覆盖；工作量中等偏上，但无新许可证 |
| **libmpv / mpv** | mpv-player/mpv | GPLv2+（可用 -Dgpl=false 出 LGPLv2.1+） | --hwdec=mediacodec / mediacodec-copy | C 库，**没有官方 Android AAR** | 活跃（37.7k★） | ★★★ 格式/字幕/网络协议最全；要自己编 .so + JNI + Surface；GPL 需开源 |
| **mpv-android** | mpv-android/mpv-android | 应用 MIT，但内置 libmpv GPL | 是（基于 libmpv） | **完整 App，不是可导入的 AAR** | 活跃（2026-09 发布） | 只能抄它的 MPVLib/BaseMPVView/JNI 代码，不能直接依赖 |
| **LibVLC** | videolan/vlc-android | 引擎 LGPLv2.1（VLC App GPLv2+） | MediaCodec | AAR：org.videolan.android:libvlc-all（稳定 3.6.2，4.0 EAP） | 活跃 | ★★ 接入最快；但本项目此前 LibVLC 在 createEngine 阶段原生崩溃、已下线，体积也大 |
| **ijkplayer** | bilibili/ijkplayer | GPL-2.0 | MediaCodec（API 16+） | 旧 AAR（jcenter 已死） | 最后提交 2021，基本停更 | ✗ 不建议（旧 FFmpeg/旧构建，维护风险） |
| **GStreamer** | gstreamer/gstreamer | LGPL | androidmedia 插件（MediaCodec） | C 库/NDK | 活跃 | ✗ 不是开箱播放器，接入重，Android 生态少 |

## 本项目的现状

- 正式版已用 **Media3**（硬解优先），测试版用自研 FFmpeg（软解）。慢的根因是软解 + 送屏/音频采样率，已分别 0.1.87/0.1.88 修，但仍待真机确认。
- 自研 FFmpeg 构建脚本 scripts/build-ffmpeg-android.sh 已带 --enable-jni --enable-mediacodec（第 73 行），所以「硬解」的依赖其实已经编进去了。
- 但 native 引擎现在用 av_find_best_stream / 默认解码器，拿到的是**软解**；要用硬解得改成按编码选 h264_mediacodec/hevc_mediacodec/vp9_mediacodec 等，并给解码器配置 Surface（FFmpeg 的 mediacodec 解码器不是打开开关就能用，需 JNI/Surface 适配与软解回退）。
- LibVLC 之前接进过测试版，但在这台机器上 createEngine 阶段原生崩溃，随后整条下线；如果重启这条线，先做独立小 demo 验证，不要直接进主流程。

## 三条路线

### 路线 A（推荐先做）：给现有 FFmpeg 引擎加 MediaCodec 硬解
- 只对 H.264/HEVC 先做；解码器名按 codec 映射；输出到现有 Surface；失败自动回退软解。
- 优点：不新增第三方依赖/许可证，保留 FFmpeg 的格式覆盖与自研可改性。
- 工作量：中等偏上（JNI Surface、格式协商、回退、首帧/seek 验证）。
- 验证：同一 4K/H.265 文件对比软解/硬解的起播、丢帧、CPU。

### 路线 B（最快验证）：测试版复用 Media3 硬解
- 正式版已有 Media3 1.11.1；测试版按「4K/HEVC 用 Media3、冷门格式用 FFmpeg」做混合，或直接让测试版也用 Media3。
- 优点：Apache-2.0、官方维护、接入成本最低；能立刻验证「硬解后是否不卡」。
- 缺点：失去自研 FFmpeg 的格式/可改性；Media3 对某些容器/编码覆盖不如 FFmpeg。

### 路线 C（换开放引擎）：libmpv 或 LibVLC
- **libmpv**：最开放的「全能型」，硬解/字幕/网络协议都强；但 GPLv2+（LGPL 模式要 -Dgpl=false，且部分功能被裁）、没有官方 AAR，要自己编 .so + JNI + Surface，工作量最大。
- **LibVLC**：LGPLv2.1、AAR 现成、硬解现成，集成最快；但体积大，且本项目有原生崩溃前科，需先做隔离 demo。

## 建议

1. **先做路线 A 的单文件 spike**（H.264 + HEVC 各一个 4K 文件，Surface 硬解，失败回退软解）——这是最贴合「开放、可改、保留 FFmpeg」目标的路线，且构建已经具备 mediacodec。
2. 同时用 **Media3** 播同一个 4K 文件做对照：如果 Media3 流畅、FFmpeg 硬解也流畅，说明路线 A 可行；如果 Media3 也卡，先查文件码率/设备解码能力。
3. **libmpv 只在「愿意接受 GPL + 大集成量」时考虑**；LibVLC 只在「想最快拿到 AAR 硬解」且能先验证不崩时考虑。
4. **ijkplayer 不建议**：GPL-2.0、2021 年后基本停更、jcenter 构建链已死。

## 参考

- Media3: https://github.com/androidx/media （Apache-2.0，MediaCodec）
- mpv: https://github.com/mpv-player/mpv ；mpv-android: https://github.com/mpv-android/mpv-android （应用 MIT，内置 libmpv GPL；README 明确「not a library/AAR」）
- LibVLC: https://github.com/videolan/vlc-android （引擎 LGPLv2.1；AAR org.videolan.android:libvlc-all，稳定 3.6.2 / 4.0 EAP）
- ijkplayer: https://github.com/bilibili/ijkplayer （GPL-2.0，最后提交 2021）
- GStreamer: https://gstreamer.freedesktop.org/ （LGPL，androidmedia）
- 本项目 FFmpeg 构建：scripts/build-ffmpeg-android.sh（--enable-jni --enable-mediacodec）
