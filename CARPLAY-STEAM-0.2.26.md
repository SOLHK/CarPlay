# CarPlay 0.2.26 · Steam

基于 DiPlay v0.2.11（6014025c653c4dae88d319ce446e0bf1ddb658ea）的完整源码构建，保留原作者及第三方许可。自定义应用名称 CarPlay，安装包名 com.shihab.diplay.steam。

## 本版功能

- 纳入上游无线 / USB 接收、运行中 Wi-Fi iAP2 定位连接修正、Wi-Fi Direct 信道选择、媒体会话 / 歌曲封面、地图卡片 / 小组件、手势及 BYD 可选高级车辆数据功能。车型专用功能仍取决于车型及系统授权。
- 安卓手机与安卓车机均可作为接收屏，设置新增 USB 主机、Wi-Fi Direct 能力显示与连接说明。手机横竖屏沿用上游 fullSensor 自适应。
- 保留 100ms 媒体启动缓冲、60fps 请求、固定双指触点槽、解码输出合并。实际帧率和连接流畅度取决于设备与网络，100ms 无法覆盖超过缓冲时长的无线中断。
- 导航、媒体可分别选择音频通道；车机是否真正提供独立导航音量需实测。
- 迁移定位过滤：仅 GPS / fused、数据年龄不超过 1500ms、精度不超过 10m；过滤无精度、过期、未来时间、异常跳点、重复定位。实际采集请求 1000ms，回调移至后台线程。默认开启上报请求，仍需精确位置权限。
- 迁移音频路由 / 使用类型 / 音量、视频网络阶段 / 解码提交 / 输出及定位状态诊断；导出严格最近一小时记录。记录含接收设备坐标，请自行保管。
- 安卓手机安装时，“接收设备 GPS”指该安卓手机；车机安装时指车机。CarPlay 未提供读取 iPhone 原始实时 GPS 的接口，也不能确认 iPhone 最终选用哪个定位源。本版不能凭软件保证车道级精度。

## 手机连接

1. 安装 APK 后打开 CarPlay，进入连接设置。无需卸载旧包；本包使用新包名，可并存。连接前退出旧版并关闭旧版自动连接 / 自启动，避免两个接收端争用蓝牙、Wi-Fi Direct 或 USB。
2. 无线模式：安卓 Android 10+，设备必须支持蓝牙及 Wi-Fi Direct；按应用连接设置完成配对，在另一部 iPhone 的“设置 → 通用 → CarPlay”中选择接收设备。允许所需蓝牙 / 附近设备权限并保持 Wi-Fi 开启。
3. 有线模式：安卓 Android 9+，需要 USB 主机 / OTG 支持及数据线，将另一部 iPhone 连接至安卓接收设备。普通仅充电接口不能接收。
4. 要上报安卓接收设备定位，授予精确位置并开启系统定位。日志在设置 → 诊断中导出。

安卓接收屏仍需要另一部 iPhone 提供 CarPlay，不能让安卓单机生成 iPhone 的 CarPlay。这里未连接用户手机 / 车辆，未进行真实配对、车道定位或音量总线验证。

## 安装与签名

旧 0.2.25 签名私钥未能恢复，所以本版采用新签名和新包名，不能覆盖旧包，也不会自动继承旧设置。后续版本须沿用本次签名。签名备份单独保存，不属于开源源码或运行认证资产；请勿公开。

## 从源码构建

环境：JDK 25、Android SDK 平台 37（SDK 包 platforms;android-37.0）、NDK 28.2.13676358；Gradle wrapper 9.5.0。local.properties 自行配置 sdk.dir。

源码不包含私有认证资产或签名材料。普通 assembleDebug 是不含认证身份的源码验证包，不能作为本次独立连接包。构建可连接版本须提供自己已有、获授权的运行认证输入：

- DIPLAY_AUTH_ASSETS_DIR 指向外部私有目录，内含 offline-mfi/identity.pk8、offline-mfi/certificate.p7b。
- ANDROID_KEYSTORE_PATH、ANDROID_KEYSTORE_PASSWORD、ANDROID_KEY_ALIAS、ANDROID_KEY_PASSWORD 指向自己的签名配置。
- 执行 ./gradlew :mobile:assembleRelease :mobile:verifyStandaloneAuthentication。

源码可执行 :shared:testDebugUnitTest、:common:testDebugUnitTest。JDK 25 中 Mockito 测试需按 Mockito 官方方式显式加载 mockito-core Java agent；设置 MOCKITO_AGENT_JAR 为依赖缓存中的 mockito-core-5.20.0.jar，再传 --init-script tools/steam-test-agent.gradle；本次另加仅用于工作区联网的代理配置，没有改动应用运行逻辑以规避测试。

本版本完整改动见 CARPLAY-STEAM.patch；测试结果见 validation/。源码依赖下载与完整构建需可访问 Google Maven / Maven Central。
