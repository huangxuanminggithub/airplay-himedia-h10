# 客厅影院投屏 · 海美迪 H10 适配版

基于 [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) 的 AirPlay 接收端，针对海美迪 H10 四代优化 H.265 硬件解码、屏幕镜像流畅度与音画同步，并提供简体中文、English 和跟随系统语言。

**当前推荐使用第八版 `h10gl60v8`。在已验证的 H10 + iPhone 屏幕镜像场景中，投屏很丝滑，声音与画面同步，延迟主观正常；此前偶发的画面、声音同时卡顿未再出现。** 这是实机测试和使用者反馈，不代表所有设备或网络的性能保证。

👉 **[下载第八版 APK](https://github.com/huangxuanminggithub/airplay-himedia-h10/releases/tag/h10gl60v8)** · [已验证场景与边界](docs/H10-V8-VALIDATION.md) · [上游说明](README-UPSTREAM.md)

## 已验证的使用场景

| 项目 | 实测范围 |
| --- | --- |
| 接收设备 | 海美迪 H10 四代，Hi3798CV200，2 GB 内存，Android 7 / API 24 |
| 固件 | HMD-2.1.6，2020-07-09 |
| 发送设备 | iPhone 14 Pro Max；系统版本由使用者报告为 iOS 26.6.2，未单独核验 |
| 投屏入口 | iPhone 控制中心 → 屏幕镜像 |
| 内容 | 主屏滑动、Safari 闪光/短音与连续运动测试页、横竖屏切换、抖音等连续有声视频 |
| 解码 | 已在日志中确认 H.265 / HEVC，使用 `OMX.hisi.video.decoder.hevc` 硬解 |
| 体验 | 使用者确认画面流畅、音画同步、延迟正常，第八版未再出现音画同时卡一下 |

以上验收记录来自 2026-10-12；第八版于 2026-10-18 整理为本仓库公开推荐版本。没有宣称完成其他机型、其他手机、长期连续运行或所有网络条件的验证。

## 帧率与能力边界

- 配置中的 **60 fps 是协商上限**，手机可以随场景发送约 30 fps；它不会把 30 帧内容自动变成 60 个不同画面。
- 界面“帧率”当前统计的是**接收到的视频帧数**，不是电视最终呈现帧率。
- 60 帧运动测试段中，解码中位数约 **59.98 fps**、应用提交约 **59.29 fps**；系统显示统计中位数约 **54.59 fps**，样本约 **49.13–57.67 fps**。因此**尚未实现或证明电视实际全程稳定显示 60 个不同画面**。
- 系统显示时间戳存在不一致，没有使用高速摄影测量，也没有测得端到端延迟的精确毫秒数。主观“低延迟”与上述软件统计应分别理解。
- 接收协议为 **AirPlay / RAOP**。本次验收针对苹果屏幕镜像；安卓、鸿蒙、Windows、Linux 等需要另外的兼容发送软件，未在这些平台完成实测。没有加入 Miracast、DLNA 或 Google Cast，也不承诺播放受 DRM 保护的内容。

## 当前版本与改进

| 项目 | 值 |
| --- | --- |
| 应用名称 / 接收名称 | 客厅影院投屏 / 运行时可设为“客厅影院” |
| 推荐构建目标 | `mvpH10Gl60` |
| versionName | `0.0.31-mvp3-l10n1-h10gl60v8` |
| 包名 | `io.github.jqssun.airplay.mvph10display60` |
| versionCode / ABI | 32 / armeabi-v7a |
| 原始实测 APK SHA-256 | `79b4c828d7c94606e14521b8bc17e3b24526ec82566814f8850f9449c443134a` |

- 使用安全的 **OpenGL 显示链路**；推荐目标关闭 `H10_DIRECT_SURFACE`。旧的海思直接显示实验路径曾触发系统合成崩溃、黑屏或重启，不作为推荐安装版。
- 增加有界离屏画面暂存，减少显示缓冲等待对取帧的阻塞，保留原音画同步时间基准。
- 将日志文件持久化移到独立工作线程，批量写入，减少音视频回调等待日志锁造成的卡顿。
- 保留静音包序号处理、FLUSH 时间基准重置、H10 HEVC 解码适配和中英文本地化。

## 安装与设置

从上方 Release 下载 `airplay-h10-v8.apk`，安装后在设置中选择语言、接收名称及开机自启。首次安装不会携带家中设备的设置或账户数据。

```bash
adb -s <设备序列号或IP:端口> install -r airplay-h10-v8.apk
```

实测配置：H.265 开启、请求分辨率 2560×1440、最高帧率 60、固定音频缓冲 40 ms、音频自动缓冲关闭、低延迟及定时输出释放开启；HDMI 输出为 1080p60。协商分辨率不等于最终镜像编码尺寸。

同一包名覆盖安装需要相同签名。Release 中提供的是实机验收的原始 APK；Actions 会从当前源码重新构建并验证签名与版本，重新构建的 APK 不保证字节级相同，也不能替代新的实机验收。公开仓库不包含签名私钥、设备应用数据、登录信息或家庭网络日志。

## 从源码构建

保留上游 GPL-3.0 许可证、历史、第三方依赖及固定版本子模块；本仓库不是 AirPin 的源码。子模块清单见 [SUBMODULE-PINS.txt](SUBMODULE-PINS.txt)，不要更新为未锁定的远端版本。

```bash
git clone --recurse-submodules https://github.com/huangxuanminggithub/airplay-himedia-h10.git
cd airplay-himedia-h10
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
chmod +x gradlew
./gradlew testMvpH10Gl60UnitTest assembleMvpH10Gl60 --no-daemon --console=plain --max-workers=2
```

需要 JDK 21、Android SDK 36、Build Tools 36.0.0、NDK 27.0.12077973、CMake 3.22.1，以及 Git、C/C++ 编译器、Ninja、Perl、curl、unzip、Python 3。`tools/setup-wsl.sh` 提供工具链安装入口；Gradle 会向锁定版本 UxPlay 应用仓库内的 native 补丁。

构建产物：`app/build/outputs/apk/mvpH10Gl60/app-mvpH10Gl60.apk`。历史 `mvpH10Display60` 是旧的直接显示实验目标，**不要用它代替当前推荐目标**。

```bash
python3 tools/check-localization.py
bash tools/test-mvp.sh host
```

第八版 33 项 JVM 回归测试覆盖帧节奏、H.265 策略、时间戳匹配、暂存队列和批量日志。自动构建入口为 [.github/workflows/h10-stable.yml](.github/workflows/h10-stable.yml)；自动构建只验证构建和回归测试，不自动完成 iPhone / H10 实机投屏验收。
