# 0.1.84-ffmpeg（versionCode 85）

自研 FFmpeg 测试版从这一版起**独立发布**——源码仓库、更新清单、Release 资产与版本号都不再和正式版共用。

## 这次改了什么

- **独立仓库**：https://github.com/gua123/mediagate-ffmpeg （公开；App 匿名读清单与资产，不需要 token）。
- **应用内更新分流**：ffmpegTest 读本仓库的 update.json（raw 两条缓存键 + jsDelivr 镜像）与本仓库 Release 资产；
  正式版仍读 gua123/mediagate，两边互不干扰。
- **独立版本线**：本构建的 versionCode / versionName 单独维护（从 0.1.84-ffmpeg / 85 起），
  发测试版不再等正式版，正式版的版本号也不会被测试版占用。
- 设置页「应用更新」里的「打开发布页」也指向本仓库 Releases。

## 升级方式（重要）

0.1.83-ffmpeg-d9（versionCode 84）及更早的测试包，更新源还指向正式版仓库，
**需要手动安装一次本包**；装好之后就能在 App 内直接检查更新。

- 直接下载：本 Release 的 APK 资产（mediagate-0.1.84-ffmpeg.apk）
- 包名：`io.github.gua123.mediagate.ffmpeg`（可与正式版共存）
- versionCode 85 > 84，可覆盖安装，数据不丢

## 功能

与 0.1.83-ffmpeg-d9 相同：自研 FFmpeg 播放引擎（surface 尺寸修复、首帧看门狗）、
网络加载与缓存策略等与正式版同源。
