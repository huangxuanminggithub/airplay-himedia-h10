# 客厅影院投屏 · 海美迪 H10 适配版

基于 [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) 的 AirPlay 接收端，针对海美迪 H10 四代优化屏幕镜像的音画同步、静音包处理和 H.265 硬解兼容性，并提供简体中文 / English / 跟随系统语言。

本仓库保存已在盒子上验收的 **h10display60v2 稳定版**及最终中文应用名称。保留上游 GPL-3.0 许可证、历史和固定版本子模块；不是 AirPin 的源码。

## 稳定版本

| 项目 | 值 |
| --- | --- |
| 应用名称 | 客厅影院投屏 |
| 包名 | `io.github.jqssun.airplay.mvph10display60` |
| versionName | `0.0.31-mvp3-l10n1-h10display60v2` |
| versionCode | 32 |
| 测试设备 | H10-G4 / Hi3798CV200 / Android 7 / API 24 |
| APK 架构 | armeabi-v7a |
| 上游基线 | `c8defdd70d7e6a04f4f1b71d353653682d594106` |

该版本保留用户确认音画同步、画面平滑、延迟正常的 v2 显示路径。之后造成音频延迟及宽高比异常的 Surface 交接实验已排除。

## 主要修改

- RAOP FLUSH 重置音视频时间基准、PCM 队列与缓冲状态。
- 消费带序号的空 RTP 音频包，修复每隔一次短音丢失的问题。
- H10 的 `OMX.hisi.video.decoder.hevc` 硬解准入与尺寸、帧率检查；H.265 硬件失败时明确报错，避免静默改用软件解码。
- 保留稳定的 SurfaceView 路径、呈现时序和冷启动修复。
- 有界重试音频输入；拒绝截断输入包并检查输出范围，区分恢复重试与解码失败。
- 设置提供“跟随系统 / 中文（简体）/ English”，涵盖主界面、设置、控件、通知和调试信息。
- 应用标签更名为“客厅影院投屏”。

## 构建

子模块已经在 Git 中锁定版本，列表见 [SUBMODULE-PINS.txt](SUBMODULE-PINS.txt)。不要使用 `git submodule update --remote`。

```bash
git clone --recurse-submodules https://github.com/huangxuanminggithub/airplay-himedia-h10.git
cd airplay-himedia-h10
```

Linux / WSL 构建环境：JDK 21、Git、CMake、Ninja、C/C++ 编译器、Perl、curl、unzip、Python 3，以及 Android SDK 36、Build Tools 36.0.0、NDK 27.0.12077973、CMake 3.22.1。

`tools/setup-wsl.sh` 可在具备 `/opt` 写入权限的环境安装 Android 工具链；它会下载 SDK 并确认所需 SDK 许可证。也可使用已有 SDK 并配置 `ANDROID_HOME`。

```bash
export ANDROID_HOME=/opt/codex-airplay-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
chmod +x gradlew
./gradlew assembleMvpH10Display60 --no-daemon --console=plain --max-workers=4
```

产物：`app/build/outputs/apk/mvpH10Display60/app-mvpH10Display60.apk`。

Gradle 在 CMake 配置前将 `app/src/main/cpp/patches/UxPlay/` 补丁应用到锁定版本的 UxPlay；补丁属于源码的一部分。历史构建目标 `mvp`、`mvpHevc`、`mvpHevc60` 保留用于参考，当前验收目标是 `mvpH10Display60`。历史说明见 [README-MVP.txt](README-MVP.txt)，其中早期默认值和旧包名不代表当前部署配置。

上游 APK 发布工作流未迁入本仓库：它构建上游 release 目标、依赖签名 secrets，并非本稳定版的构建入口。请使用以上本地构建命令。

## 安装与配置

```bash
adb -s <设备序列号或IP:端口> install -r app/build/outputs/apk/mvpH10Display60/app-mvpH10Display60.apk
adb -s <设备序列号或IP:端口> shell am start -n io.github.jqssun.airplay.mvph10display60/io.github.jqssun.airplay.MainActivity
```

这是独立包名的 debug 构建。不同电脑默认生成的 debug 签名不同；替换现有安装时必须使用相同签名，不能只凭包名和版本号保证可覆盖安装。本仓库不包含签名私钥、账户信息或设备应用数据。

现有盒子的部署设置：接收名称“客厅影院”、开机自动启动开启、H.265 开启、请求分辨率 2560×1440、最高帧率 60、固定音频缓冲 40 ms、低延迟和定时输出缓冲释放开启、音频自动缓冲关闭。

**接收名称及开机自启属于运行时设置。** 当前构建目标的首次安装仍使用测试名称且 BootReceiver 默认关闭；重新构建或全新安装后，请在应用设置中填写“客厅影院”并启用开机自启。已有安装的设置不在源码中，也没有上传。

## 验证与边界

```bash
python3 tools/check-localization.py
# 完成一次构建、应用 UxPlay 补丁后：
bash tools/test-mvp.sh host
bash tools/test-mvp.sh arm
```

测试覆盖音频输入边界、时间基准、FLUSH、静音序号、重传、回绕与并发快照。`arm` 生成可推送到 Android 7 盒子运行的 ARMv7 测试文件；RAOP 测试中的 AES 是测试桩，不验证密码学。

实机发送端为 iPhone 14 Pro Max / 用户报告 iOS 26.6.2。已核实协议 HEVC、应用海思 HEVC 解码器、驱动 H265 三层证据，并观测到约 60 fps 的接收/硬解阶段；用户确认稳定版音画同步、无漏音/卡顿、延迟正常。发送端在部分场景会降到 30 fps。

HDMI 1080p60、接收帧率、解码帧率、释放帧率和电视实际呈现帧率是不同指标。**尚未证明电视全程显示 60 个不同画面，也没有测量端到端延迟的精确毫秒数。** 软件计数和主观验收不能代替这些测量。

接收协议为 AirPlay / RAOP：支持苹果屏幕镜像、音频及兼容视频发送；其他系统需要兼容发送端。没有加入 Miracast、DLNA 或 Google Cast；盒子内置的相应服务属于其他应用。Apple TV 等 DRM 保护视频不支持。更多上游功能见 [README-UPSTREAM.md](README-UPSTREAM.md)。

## 最终安装包记录

2026-10-12 盒子已安装的中文名称 APK：

```text
SHA-256 ff5f8781e69354fbb755930db0933ad9c5977b1e77cdcd07ef861fa1b284e6a8
```

最终 APK 在原已验收稳定版上仅替换名称资源并重新签名，DEX 与原生库逐字节保持一致。重新从本仓库编译会得到同一源码版本，但构建环境、D8 增量状态与签名可能导致 APK 字节不同。本次上传源码，不发布安装包。

## 许可证与上游

GPL-3.0，见 [LICENSE](LICENSE)。AirPlay / RAOP 来自 UxPlay，另使用 FFmpeg、libplist、openssl-cmake 等子模块及其各自许可证。原项目作者、历史与依赖版权保留。本项目与 Apple 无隶属关系。
