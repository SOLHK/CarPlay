# CarPlay 0.2.27 · Steam

纠正 0.2.26 超出“新增上游功能”范围的改版。使用恢复的 0.2.25 安装包和补丁源码作为定制界面及参数的参考，保留当前完整 Gradle 项目中的接收功能。

- 首页恢复原定制版的无线卡片、USB 与设置卡片以及横屏 / 竖屏排列，移除上游新首页的大图标与双行布局。首页、设置、关于、连接提示、通知资源及诊断保存目录统一为 CarPlay；制造商默认 Steam，型号默认 CarPlay。关于保留上游和第三方许可致谢。
- 恢复 0.2.25 的 ResponsiveUi 和 LocationPanel：小屏 / 车机字号及尺寸缩放；设置可见时每秒刷新 GPS 面板，离开后停止刷新。
- 移除主设置中的右舵选项，恢复原定制版默认 80% 分辨率和中文。只进行一次配置迁移，之后保留用户修改的设置。
- 上游手势、安卓接收屏说明和能力显示、车型扩展等集中到“新增功能 → 手机接收与上游扩展”，保留主界面的原有连接与显示入口。
- 旧定制版关闭的自动比亚迪导航联动保持关闭。车机扩展可在新增功能中自行选择。车型条件和系统权限限制仍保留。
- 修复 0.2.26 音乐缓冲标签及选项数量错位：100 / 300 / 500 / 1000 毫秒各对应自己的数值，默认 100ms；60fps 请求、定位质量过滤和一小时诊断保留。

## 安装

与 0.2.26 使用相同包名 com.shihab.diplay.steam 和相同签名，可以直接覆盖 0.2.26。0.2.25 的旧签名私钥仍不在本次备份中，不能覆盖旧包。

安卓接收屏需要另一部 iPhone 提供 CarPlay。无线需要 Android 10+、蓝牙及 Wi-Fi Direct；有线需要 Android 9+ 和 USB 主机 / OTG。真实手机 / 车机连接未在本环境测试。GPS 过滤不能保证车道级精度。

## 构建

JDK 25、Android SDK 37、Gradle wrapper 9.5.0；默认从源码用 NDK 28.2.13676358 构建原生库。原生代码本次没有改动。

本次为了恢复清理后的构建环境，用 CARPLAY_PREBUILT_NATIVE_DIR 指向从授权 0.2.26 APK 提取的原有两组原生库（arm64-v8a、armeabi-v7a、x86_64）；这些文件逐字节保持一致，不随源码归档。未设置该变量时正常从源码编译。

运行认证和签名输入仍按原项目 BUILD.md 提供，均不包含在源码中。运行 :mobile:assembleRelease、:mobile:verifyStandaloneAuthentication；测试 :shared:testDebugUnitTest、:common:testDebugUnitTest。JDK 25 测试 Mockito agent 可参照 tools/steam-test-agent.gradle 配置。

源码源自 DiPlay v0.2.11：6014025c653c4dae88d319ce446e0bf1ddb658ea。保留 GPL / AGPL 及第三方许可。
