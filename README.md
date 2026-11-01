# mediagate-ffmpeg — 自研 FFmpeg 播放引擎测试版

Android 多协议媒体播放器（本地 / SAF、WebDAV、SFTP、FTP）的**测试构建**：与正式版同源，
只把播放内核换成自研 FFmpeg 引擎（正式版仍然只保留 Media3）。

- 正式版仓库：https://github.com/gua123/mediagate
- 本测试版包名 `io.github.gua123.mediagate.ffmpeg`，可与正式版共存；**版本线独立**（`0.1.84-ffmpeg` / versionCode 85 起）
- **应用内更新**：读本仓库的 [update.json](update.json) 与本仓库 Releases（公开、匿名可取，App 不需要 token）
- 计划与验收标准见 [docs/plan.md](docs/plan.md)；工作区约定见 [AGENTS.md](AGENTS.md)

## 构建

    source scripts/env.sh                  # 进入本项目环境（私有 JDK / Gradle / SDK / NDK）
    ./gradlew :app:assembleFfmpegTest      # 测试版产物 app/build/outputs/apk/ffmpegTest/app-ffmpegTest.apk
    ./gradlew :app:assembleDebug           # 通用调试包

    # native 库 16 KB 页对齐自检（Android 15+ 必需）
    bash scripts/check-page-align.sh app/build/outputs/apk/ffmpegTest/app-ffmpegTest.apk

## 发版（应用内更新）

    # 先改 app/build.gradle.kts 里的 ffmpegVersionCode / ffmpegVersionName，再：
    bash scripts/publish-update.sh

脚本会：构建 ffmpegTest → 建 GitHub Release（tag `v<版本名>`）→ 传 APK → 写 update.json → 推送。
App 端匿名读清单，不需要 token。

## 环境隔离（不影响本机其它项目）

| 组件 | 位置 | 说明 |
| --- | --- | --- |
| JDK 21 | `.toolchain/jdk-21` | Temurin 解压版，不写系统 PATH |
| Gradle 9.8.0 | `.toolchain/gradle-9.8.0` + wrapper 预置 | `GRADLE_USER_HOME` 也在项目内 |
| Android SDK | `.toolchain/android-sdk` | platform 37.2 / build-tools 37.0.0 / platform-tools |
| NDK / CMake | `.toolchain/android-sdk/{ndk,cmake}` | NDK r30 + CMake 4.1.2，只给本项目 native 构建用 |

系统层面**不安装任何 JDK/SDK**；公共 `/opt/android-sdk` 只作为 sdkmanager 的下载来源。
依赖仓库在 `settings.gradle.kts` 里配了阿里云镜像优先、官方源兜底。

## 版本基线

AGP 9.4.1（内置 Kotlin，不再声明 kotlin.android 插件）· Gradle 9.8.0 · Compose BOM 2026.09.00 ·
compileSdk 37.2 / targetSdk 36 / minSdk 33 · Media3 1.11.1 · Room 2.8.5 · OkHttp 5.5.0 ·
JSch 2.28.7（mwiede）· Commons Net 3.13.0 · Coil 3.6.3。
