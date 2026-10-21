# CarPlay

SOLHK 正在开发的独立 Android 车机 CarPlay 接收端。本次源码从 [DiPlay 0.2.21](https://github.com/shihabal3amri/DiPlay/tree/6d3b8a8ce927cb4b115b8c3881f17619433e93b1) 重新开始；原 SOLHK/CarPlay 0.2.33 的文件不属于当前源码树。连接核心与设置持久化沿用上游实现，应用自身界面正在重构。

本应用及原创图标**并非 Apple 官方产品，也未经 Apple 认证**。投射的 CarPlay 画面由 iPhone 绘制，Android 端改动只影响接收端自己的页面。

- 应用显示名称：CarPlay；正式包名 `com.solhk.carplay`，Debug 包名 `com.solhk.carplay.hudtest`。它不会覆盖旧版 DiPlay 或 SOLHK 测试包，已有设置不会自动迁移。
- 深浅色界面使用统一颜色 Token。连接、音视频、Siri、车辆功能及诊断路径仍需本分支 CI 和真机验收。
- 普通 Debug APK 不包含配件认证材料，可安装查看界面，但不能独立完成 iPhone 连接。
- 本分支尚未对 iPhone 17 Pro Max、iOS 27.2 RC、USB-C 或车机完成真机测试。见[真机验收记录](docs/IPHONE_17_IOS_27_2_RC.md)。

构建环境需要 JDK 25、Android SDK 37、NDK 28.2.13676358。命令与认证输入要求见 [构建指南](docs/BUILD.md)。源码保留 GPL-3.0、AGPL-3.0 及第三方许可；[上游中文 README](docs/UPSTREAM_README_0.2.21.zh-CN.md)仅作为历史资料，不代表本分支已经通过同样的测试。
