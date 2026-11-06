# 0.1.89-ffmpeg（versionCode 90）

自研 FFmpeg 引擎加入 **MediaCodec 硬解**（H.264 / HEVC / MPEG-4 / MPEG-2 / VP8 / VP9 / AV1）。

## 怎么做的

- 本项目 FFmpeg 构建本来就带 --enable-jni --enable-mediacodec；这次把解码器从默认软解改成按编码找 h264_mediacodec / hevc_mediacodec 等；
- JNI_OnLoad 把 JavaVM 交给 FFmpeg；Kotlin 的 android.view.Surface 以全局引用存下来；
- 播放入口先试硬解，失败（没有 Surface / 设备不支持 / 编码不匹配）自动回退软解，不会因为硬解不可用而播不了；
- 硬解帧是 AV_PIX_FMT_MEDIACODEC，直接 release 到 Surface，不再走 sws_scale + 逐行拷贝；丢帧/seek 时显式 release(0)，避免输出缓冲占满卡死；
- 诊断卡「画面 …」会显示实际解码器名：h264_mediacodec / hevc_mediacodec = 硬解，h264 / hevc = 软解。

## 已知点

- 首版硬解直接输出到 Surface，画面比例可能被拉伸（软解路径的 FIT 黑边不会生效）；先看性能，比例/缩放下一版再补。
- 硬解初始化失败会回退软解；如果硬解初始化成功但运行中不出帧，第一帧看门狗仍会报错，请把诊断卡发我。

## 验证

播一个之前卡的 4K / H.265 视频，看：

1. 是否还像慢放；
2. 诊断卡「画面」后的解码器名是不是 *_mediacodec；
3. 丢帧/音频欠载/音画偏差是否明显下降。

本版同时包含 0.1.87 送屏优化与 0.1.88 音频实际采样率修复。
