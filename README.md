# 威马桌面 · WM Launcher

**为 Android 6.0.1 车机设计的轻量桌面：功能精简，安装包约 1.26 MiB。**

把应用、音乐和车辆信息放到一个清晰的首页，大图标、大字体，支持横屏和竖屏。基于开源 Dashline 适配，不是威马官方软件。

[下载最新版](https://github.com/naojiren/wm-launcher/releases/latest) · [wm23 发布说明](https://github.com/naojiren/wm-launcher/releases/tag/v0.1.0-wm23)

## 小体积，保留常用功能

wm23 独立版 APK 为 **1,320,229 字节，约 1.26 MiB / 1.32 MB**。体积来自实际发布文件，源码包大小不计入 APK。

- **原生界面**：Kotlin + Android Views，使用系统字体和矢量图标。
- **功能精简**：去掉在线天气；音乐、导航等直接调用车机已有应用，不把播放器、地图或浏览器打包进桌面。
- **依赖少**：不依赖 Google Play 服务，无广告 SDK，无在线字体或图标下载。
- **减少后台工作**：车辆信息在桌面前台读取，离开桌面后释放相关监听。

## 日常使用

- 自动识别已安装应用；首页默认显示第三方应用，每排 3 个，完整显示 3 排，更多应用左右翻页。
- 完整应用抽屉按“第三方 / 原厂应用 / 全部”分类；长按可隐藏、打开系统应用详情或发起卸载。
- 保留 3 个自定义快捷入口，以及车辆控制、原厂功能、音乐、广播、电话、导航入口。
- 首页音乐卡片显示曲目和播放状态，提供播放/暂停、上一首、下一首、音量和进度操作。优先使用当前来源支持的直接控制，兼容 CarPlay；已识别的来源拒绝操作时不会转交其他播放器。
- 普通键盘左/右键和桌面按钮控制当前音乐来源。兼容威马实体左右键的原厂专用通知：松开左键上一首，右键下一首；快速连按不会漏掉。原厂播放器保留原厂按键处理，避免重复切歌；离开首页释放监听。
- 深海冰蓝主题、大字和较大的点击区域，适配竖屏与横屏。
- 在兼容的威马固件和权限下，可显示电量、系统预估里程、按 280 × 电量百分比计算的实际预估里程、四轮胎压、TRIP A/B、总里程，以及电池和 LTE 详情。
- Home 使用系统持久首选，开机完成后自动打开威马桌面。启动期间仅短暂检查原厂首页的后续覆盖；用户主动打开应用会取消检查，其他前台应用也不切换。完成后服务停止。威课堂入口与顶部常开设置保留。

## 界面预览

下图为 wm19 安卓 6 模拟器的示例数据，wm23 保留此布局。

<img src="docs/screenshots/home-wm19.png" alt="威马桌面首页：三列三排应用" width="360">

## 安装与兼容

Release 默认提供独立版 **`com.wmhome.launcher`**，适配目标为 Android 6.0.1 / API 23。下载 APK 后在车机安装；同签名覆盖升级可保留桌面配置。

车辆数据依赖对应固件的原厂接口及权限，部分数据需要系统特权应用安装和原厂权限授权。普通安装仍可使用应用桌面，车辆信息可能显示不可用。威课堂跳转与默认桌面设置沿用本机 ADB 通道，需要车机 ADB 开关开启。

TRIP A/B 当前提供显示，尚未接入仪表屏小计清零；系统预估读取原厂值，实际预估按固定 280 km 系数计算。学习强国兼容分支保留在源码中，默认下载使用独立版。

## 自行构建

环境：JDK 21、Android SDK 36、Build Tools 36.0.0。项目使用 Gradle Wrapper 8.13，minSdk / targetSdk 均为 23。首次构建会下载公开构建依赖。

```bash
git clone https://github.com/naojiren/wm-launcher.git
cd wm-launcher
./gradlew :app:assembleLegacyDebug
```

Windows 使用 `gradlew.bat`。调试 APK 位于 `app/build/outputs/apk/legacy/debug/`。

```bash
# Android API 23 单元与布局检查
./gradlew :app:testLegacyDebugUnitTest :app:testXxqgDebugUnitTest

# Release 和静态检查；签名配置独立提供
./gradlew :app:assembleLegacyRelease :app:lintLegacyRelease
```

正式签名可参照 `keystore.properties.example`，或提供 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 环境变量。发布用私钥不包含在仓库中。

wm23 两种分支共 **164 项单元/布局检查通过**（以 API 23 为主，另含其他安卓版本不得触发的边界检查），正式 APK 的 v1/v2 签名有效。专用安卓 6 模拟器使用正式包和普通应用 UID 完成 **15 项原生检查**，包括实际重启后无需按 Home 就显示桌面、开机广播立即打开、原厂首页稍后覆盖时返回、不打断其他前台应用、过期窗口和取消标记不切换，以及服务自行停止。见 [wm23-checks.json](docs/wm23-checks.json)。原厂卡片为自编替身；wm23 已安装到实车，开机效果仍待用户测试。

从 wm21 升级增加了开机广播权限和有限启动服务，没有定时巡检。默认设置修复最多五次，启动首页检查仅在短暂窗口进行三次，完成后服务停止。仅适配原厂 Android 6 工程权限接口，不禁用原厂桌面，不直接改写系统 XML。接口不可用时保存并核验普通默认，并显示回退状态。开机广播到来前，原厂首页或固件启动画面仍可能短暂出现。

用户已确认 wm22 的 Home 指向固定成功，检查见 [wm22-checks.json](docs/wm22-checks.json)。保留 wm21 已由用户实车确认有效的实体左右切歌，媒体检查见 [wm21-checks.json](docs/wm21-checks.json)，布局检查见 [wm19-checks.json](docs/wm19-checks.json)。正式包不含 debug 测试入口，车辆日志、原厂二进制和密钥未公开。

## 开源与致谢

基于 [Dashline](https://github.com/metehankaygsz/dashline)，上游基线 `be4c98fcc649abdec55e687c5e7cda02d9c73001`，原作者 © 2026 Metehan Kaygısız。此适配新增威马车辆信息、原厂入口、应用分类、大字布局、横竖屏适配和精简后的离线首页。

项目遵循 **GPL-3.0-only**，见 [LICENSE](LICENSE)。Release 同时提供对应完整源码包。

图标来自 Tabler Icons（MIT）与 Lucide Icons（完整 ISC / Feather MIT 声明）。固定来源、SVG 原件和完整许可保留在 [third_party](third_party/)，APK 中也附带图标许可。上游说明保留在 [UPSTREAM-README.md](docs/UPSTREAM-README.md)。
