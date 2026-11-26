# Moonlight V+ 开发指南

Moonlight V+ 是 Android 串流客户端。在手机、电视或 SHIELD 上，它接收并播放主机的画面和声音，把手柄、键盘和触摸输入发回主机。本机配套的 Sunshine 源码在同级目录 `../sunshine/`，主机服务安装在 `C:\Program Files\Sunshine`。

## 代码入口

Android 应用代码以 `app/src/main/java/com/limelight/` 为根目录。

| 要改的功能 | 从哪里看 |
| --- | --- |
| 串流启动与会话 | `Game.kt` 管理串流界面，`nvstream/NvConnection.kt` 建立连接，`ConnectionCallbackHandler.kt` 处理连接回调。 |
| 播放与输入 | 视频解码看 `binding/video/MediaCodecDecoderRenderer.kt`，音频输出看 `binding/audio/`，手柄和键鼠输入看 `binding/input/`。 |
| JNI 和传输协议 | `nvstream/jni/MoonBridge.java` 对接原生层，C 代码在仓库的 `app/src/main/jni/moonlight-core/`。其中的 `moonlight-common-c/` 子模块处理 RTSP 协商与 RTP 接收。 |

插帧桥接在 `framegen/`，`pyrowave-runtime/` 构建并打包 PyroWave 原生库，USB/IP 后端在 `usbip-backend/`。需要主机配合的功能可查[兼容矩阵](docs/COMPATIBILITY.md)。

## Windows 构建与测试

使用仓库自带的 Gradle Wrapper。工具链按 Android CI 配置，使用 JDK 17、Android SDK 36、Build Tools 36.0.0、NDK 28.2.13676358 和 CMake 3.22.1。[Android CI](.github/workflows/android-ci.yml)

新检出仓库时运行 `git submodule update --init --recursive`。另按 README 获取 `moonlight-audio-haptics`，检出 CI 中 `AUDIO_HAPTICS_SDK_REF` 指定的版本。Gradle 在加载项目时就需要这个 SDK，单元测试也会用到它。[从源码编译](README.md#从源码编译) · [Gradle 模块配置](settings.gradle)

本机可用 Android Studio 自带的 JDK 17，Android SDK 路径已写在 `local.properties` 的 `sdk.dir`。在仓库根目录的 PowerShell 中设置本次构建使用的 JDK 和触觉 SDK。如果按 README 把触觉 SDK 克隆到了同级目录，第二行的路径改为 `../moonlight-audio-haptics`。

```powershell
$env:JAVA_HOME = 'D:\develop_tool\Android\Android Studio\jbr'
$env:AUDIO_HAPTICS_SDK_DIR = (Resolve-Path '.deps/moonlight-audio-haptics').Path
.\gradlew.bat :app:assembleNonRootDebug
.\gradlew.bat :app:testNonRootDebugUnitTest :app:lintNonRootDebug
```

APK 输出到 `app/build/outputs/apk/nonRoot/debug/`。JVM 单测在 `app/src/test/`，Android 设备测试在 `app/src/androidTest/`。下面的命令只运行 MAT 播放队列的单测。

```powershell
.\gradlew.bat :app:testNonRootDebugUnitTest --tests 'com.limelight.binding.audio.MatCarrierPlayerTest'
```

## 两端联调

`moonlight-common-c` 是两端共用的独立 Git 仓库。本机在平级目录 `../moonlight-common-c/` 修改和提交公共代码。`app/src/main/jni/moonlight-core/moonlight-common-c/` 是指向它的 Windows 目录联接，Sunshine 的本地构建也读取同一批文件。[公共库开发指南](../moonlight-common-c/AGENTS.md)

- 在主仓库运行 `git -C ../moonlight-common-c status` 查看公共库改动。这两个本地工作树已跳过公共库的子模块更新，并对其 Git 索引项设置了 `skip-worktree`。CI 和普通检出仍使用各自锁定的提交，客户端的锁定值用 `git rev-parse HEAD:app/src/main/jni/moonlight-core/moonlight-common-c` 查看。升级 CI 依赖时，在普通检出中更新该指针。[Android 原生编译清单](app/src/main/jni/moonlight-core/Android.mk)
- Android NDK 把公共库和本仓库的 JNI 桥接代码编入 `libmoonlight-core.so`。客户端协商、RTP 收包和重组在公共库修改，Android 硬解码和音频播放在本仓库修改。改协议字段时，同时检查 Sunshine 的 `src/rtsp.cpp` 和 `src/stream.cpp` 如何协商和发送。[主机开发指南](../sunshine/AGENTS.md)
- 排查 MAT 时，沿着 `moonlight-common-c` 接收、JNI 的 `mat_carrier_bridge.c`、`binding/audio/` 下的 `MatCarrierPlayer` 和 `MatAudioTrackSink` 追到 HDMI。联调 SHIELD Pro 和 Samsung Q900A 时，分别记录软件测试、设备载波输出、回音壁显示的格式和实际听感。[MAT 链路说明](docs/MAT-HANDOFF.md)

## 协作习惯

优先用简单直接的方案。新增校验、兜底或兼容分支时，说清它在实际调用中防止哪个错误。向用户先用白话给出结论，必要时再展开。
