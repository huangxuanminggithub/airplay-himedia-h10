HiMedia H10 四代屏幕镜像同步 MVP

基础版本：jqssun/android-airplay-server v0.0.31
基础提交：c8defdd70d7e6a04f4f1b71d353653682d594106
目标设备：Hi3798CV200，Android 7 / API 24，armeabi-v7a。

构建
1. 克隆项目并初始化固定版本的子模块：git submodule update --init --recursive
2. Linux / WSL Ubuntu 安装 JDK 21、cmake、ninja-build、build-essential、git、perl、curl、unzip。
3. 执行 bash tools/setup-wsl.sh，安装 Android SDK、NDK 和 CMake。
4. 执行 bash tools/build-mvp.sh。
5. APK 位于 app/build/outputs/apk/mvp/app-mvp.apk。

安装
adb -s 0123456789 install -r app/build/outputs/apk/mvp/app-mvp.apk
adb -s 0123456789 shell am start -n io.github.jqssun.airplay.mvp/io.github.jqssun.airplay.MainActivity

MVP 使用独立包名 io.github.jqssun.airplay.mvp，名称 AirPlay Server MVP，
投屏名称 Android AirPlay MVP。原版仍可保留；运行测试时只启动一个接收服务。
MVP 默认关闭开机启动，启用固定 40 ms 音频缓冲和调试日志。
安装使用 debug 签名，未做正式发布签名，也未与原版发布包共用签名。

修改
- RAOP FLUSH 清理解码器、PCM 队列、缓冲状态和音视频时间基准。
- 用已提交 PCM 的 PTS、单调时钟和估算音频输出延迟安排视频呈现。
- 将 MediaCodec 的单调呈现时间传递到最终 EGL 显示缓冲。
- 以 PTS 增量和实际经过时间之差判断跳转，正常的长静音不重启音频输出。
- Android 7 查询 AudioSystem.getOutputLatency(STREAM_MUSIC)，本机报告 60 ms。
- 取消根据估算时钟激进丢弃已解码视频帧，避免抹去短闪光。
- MVP3：接收器保留并消费带序号的空 RTP 包，静音不会被当成丢包堵住下一段短音。
  补丁位于 app/src/main/cpp/patches/UxPlay/0007-consume-empty-audio-rtp-sequences.patch。
- MVP3：调试日志输出音频包、解码 PCM、进入队列和输出的累计计数。
  只统计幅度超过 128 的 PCM 样本，不保存原始音频，实时音频回调不写日志。

验证边界
tests/timeline_clock_test.cpp 检查缓冲、FLUSH、PTS、时钟失效与并发快照。
tests/raop_silence_test.c 直接编译接收器源码，覆盖 12 段短音与两种空 RTP 包、
重传模式、序号回绕和重复包；其中 AES 为测试桩，不测试加密或解密。
完成上述构建（它会应用 UxPlay 补丁）后，运行 bash tools/test-mvp.sh host。
运行 bash tools/test-mvp.sh arm，生成两个 tests/*-armv7 可执行文件，
用 ADB 推到盒子的 /data/local/tmp，chmod 755 后直接运行。
它不能验证屏幕和扬声器的实际同步，必须另做 iPhone 屏幕镜像实测。

OpenSL ES 在本机没有可用的硬件播放时间戳。同步计算使用系统延迟估计，
没有包含电视或功放的额外处理延迟，不能据此宣称零延迟。
MVP 为调试构建，其性能不能直接代表优化后的正式发布包。
基础版本 Android lint 有既存的 API 级别和 Media3 opt-in 等错误；详见验证目录。

2026-10-12 实机结果
MVP3 已通过本机 iPhone 控制中心屏幕镜像的 24 秒闪光/短音测试。
用户确认每次都有短音、基本同步，手机到电视的画面延迟很小、基本可以接受。
软件日志确认 12 段短音均解码、入队并送出，测试段没有 PCM 丢弃或解码失败。
没有测量端到端延迟的精确毫秒值；连续普通媒体播放期间有一次解码失败计数，
长期播放、多次重连和正式发布构建仍需要后续验证。

2026-10-12 汉化版
版本：0.0.31-mvp3-l10n1，versionCode 32，包名和签名与 MVP3 相同，可直接更新。
设置 -> 界面 -> 语言：跟随系统、中文（简体）、English。
默认跟随系统，中文系统显示中文，未提供翻译的语言使用英文。
手动选择的语言由 AndroidX AppCompat 保存，重启应用后继续生效。
覆盖首页、设置、播放控件、提示、通知与调试信息；协议和诊断日志保留原始文本。
使用 AppCompatActivity / AppCompatDelegate 兼容 Android 7，Android 13 及以上
同时向系统声明支持的应用语言。语言切换重建界面并保留当前设置标签页。
音视频接收、解码、同步实现保持已通过实测的 MVP3 版本。
翻译校验：python tools/check-localization.py。
原始 MVP3 安装包、源码快照和验证记录保存在 ../airplay-localization-20261012。
官方实现说明：https://developer.android.com/guide/topics/resources/app-languages

2026-10-12 H10 HEVC 独立测试包
构建：./gradlew assembleMvpHevc --no-daemon --console=plain --max-workers=4。
版本：0.0.31-mvp3-l10n1-h10hevc1；包名 io.github.jqssun.airplay.mvphevc。
安装包：app/build/outputs/apk/mvpHevc/app-mvpHevc.apk。
该包与已验证稳定版 io.github.jqssun.airplay.mvp 并存，开机接收器关闭。
测试时只运行其中一个服务；用 am force-stop 停止另一个，然后打开所需版本。
稳定版 APK、源码和设置备份位于 ../airplay-h10-hevc-20261012/baseline。

仅 H10-G4 / bigfish / API 24 的 OMX.hisi.video.decoder.hevc 获得额外准入，
同时检查目标尺寸与帧率。其他机型与稳定版的原有 HEVC 策略保持不变。
测试包初始化为 Android AirPlay H.265 Test、2560x1440、最高 30 fps，
用于触发支持 HEVC 的苹果发送端；这是镜像请求，HDMI 显示模式不随之改变。
该包禁止 HEVC 软件回退；若硬件初始化失败会明确记日志，需要关闭 H.265
并重新连接，或返回稳定版。H.264 的原有软件回退保留。
音频、RTP 静音修复与同步实现保持不变，固定音频缓冲仍为 40 ms。

真正生效的证据必须同时包括 video_set_codec: 2 (h265_enabled=1)、
Video codec started: video/hevc (...OMX.hisi.video.decoder.hevc...)、
以及 /proc/msp/omxvdec 中正在工作的 Protocol: H265。
H.265 开关或启动时选中的解码器仅代表允许，不代表发送端实际发送了 HEVC。
复测页面：http://10.0.0.43:8787/hevc.html；使用控制中心屏幕镜像。
HEVC 验证结果及边界记录在 ../airplay-h10-hevc-20261012。

H10 HEVC 实机验证已完成：iPhone 14 Pro Max / iOS 26.6.2 控制中心屏幕镜像，
协议选择 HEVC、应用启动海思 HEVC 解码器、驱动显示 H265 三项证据同时成立。
本轮实际竖屏视频为 664x1440，驱动源帧率约 30 fps，HDMI 仍为 1080p60。
用户反馈闪光/短音音画同步，手机闪光结束时电视闪光开始，延迟接近稳定版且可接受。
未用高速摄影测量精确端到端延迟；不能把驱动 DelayTime 或音频时钟估计当作该值。
最高 30 fps 是本测试包初始化设置。设备能力报告支持 HEVC 1080p60，
但本轮 2560x1440 请求和实际镜像链路的 60 fps 尚未实测；保留已通过的 30 fps 参数。

H10 HEVC 60 fps 独立试验
构建：./gradlew assembleMvpHevc60 --no-daemon --console=plain --max-workers=4。
包名 io.github.jqssun.airplay.mvphevc60，版本 0.0.31-mvp3-l10n1-h10hevc60v1。
初始化名称 Android AirPlay H.265 60 Test，请求 2560x1440 / 60 fps。
30 fps 版本仍为 io.github.jqssun.airplay.mvphevc，原 APK、源码和设置保存于
../airplay-h10-hevc60-20261012/baseline30。两个接收服务依次启动，避免端口冲突。
本次仅新增构建类型及其初始化帧率/名称/资源，沿用相同解码选择与同步实现。
测试包关闭开机接收器，原生库与已验证 30 fps 版逐个字节相同。
复测 http://10.0.0.43:8787/hevc60.html，仍用控制中心屏幕镜像。
页面持续移动条用于避免静态画面影响帧率观察；网页动画 fps 不代表投屏 fps。
实机能力探针确认 2560x1440@60 获得硬件选择许可，不能据此宣称运行达到 60 fps。
需同时记录应用接收帧率、驱动实际输出计数增量与显示提交记录，再结合现场同步反馈。
验证记录保存在 ../airplay-h10-hevc60-20261012。

60 fps 试验实机结果：iPhone 实际协商 HEVC，应用启动 OMX.hisi.video.decoder.hevc，
驱动显示 H265；连续竖屏阶段实测硬解输出约 59–61 fps，接收日志约 60 fps。
用户确认音画同步、无漏音或卡顿，与 30 fps 版相比延迟明显减小。
显示提交日志在连续测试阶段约 50–55 fps，未证明电视全程呈现 60 个不同画面。
SurfaceFlinger 此机返回零时间戳，无法用该接口定量确认物理呈现速率。
输入 dropped=0 不覆盖 SurfaceTexture 合并帧；不能把界面 fps 当作电视实际帧率。
测试后收尾时 USB/TCP ADB 短暂掉线，网络 ADB 随后恢复，应用 PID 保持不变。
已保留实测期日志、截图与恢复后的最终状态，并重新校验 30 fps 安装包和设置。
