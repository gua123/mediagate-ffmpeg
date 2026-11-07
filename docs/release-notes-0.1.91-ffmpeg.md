# 0.1.91-ffmpeg（versionCode 92）

真机确认 0.1.90 的“自动切回软解”生效（能看，但硬解仍失败）。这次定位到**续播 seek 的时机**。

## 根因

0.1.90 虽然把“起播位置”通过 play(startMs) 传给 native，但播放页的 applyResume() 仍然在
prepare()/play() **之后**才调用 seek。而 NativeEnginePlayer 在 prepare 完成后就会起 native 播放线程
（此时 MediaCodec 已打开）。于是又变成“硬解打开后再 seek + flush”，和前一次一样：0 帧上屏、看门狗超时。

## 改动

1. applyResume() 提前到 setMedia() 之后、prepare()/play() **之前**：NativeEnginePlayer 会把它记成
   pendingSeek，等 startIfReady 起播时通过 play(startMs) 在打开解码器前 seek。
2. 面包屑改为打印**实际内核**（created.kind）；请求与实际不一致时写“请求 X”。测试版不会再显示误导的「内核 Media3」。
3. 硬解开关键换成 hardware_decode_v2：0.1.90 自动回退写入的 false 不会带过来，本版重新默认开硬解。

## 验证

再播同一个 1080x1920 视频（尤其续播到 36 秒）：

1. 日志「开始播放」应显示「内核 自研引擎（请求 Media3）」；
2. 诊断卡「画面」后应是 h264_mediacodec（硬解）；
3. 若又出现「已自动切回软解」，把诊断卡发我——说明不是 seek 时机，下一步查 Surface/解码器本身。
