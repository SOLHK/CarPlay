# CarPlay

SOLHK 正在开发的独立 Android 车机 CarPlay 接收端。CarPlay 0.2.34 从 [DiPlay 0.2.21](https://github.com/shihabal3amri/DiPlay/tree/6d3b8a8ce927cb4b115b8c3881f17619433e93b1) 同步接收端源码与测试至 [DiPlay 2026-10-24 主线](https://github.com/shihabal3amri/DiPlay/tree/9d89633035d1add5b044ba3de5e57dd078bf2511)；原 SOLHK/CarPlay 0.2.33 的文件不属于当前源码树。连接核心与设置持久化沿用上游实现，应用自身界面正在重构。

本应用**并非 Apple 官方产品，也未经 Apple 认证**。启动图标采用绿色 CarPlay 标记的矢量版本，见[图标说明](docs/THIRD_PARTY_NOTICES.md#carplay-icon)。投射的 CarPlay 画面由 iPhone 绘制，Android 端改动只影响接收端自己的页面。

- 应用显示名称：CarPlay；正式包名 `com.solhk.carplay`，Debug 包名 `com.solhk.carplay.hudtest`。它不会覆盖旧版 DiPlay 或 SOLHK 测试包，已有设置不会自动迁移。
- 接收端主页、设置页和音乐页使用统一深浅色 Token。音乐页读取当前 iPhone 会话的真实媒体信息，并通过原有控制器发送播放指令；断开连接时不伪造曲目，播放按钮不可操作。
- [`40d14f5e` 的 Android CI](https://github.com/SOLHK/CarPlay/actions/runs/39570837683) 已通过单元测试、Lint 与源码版 Debug APK 构建；这不代表车机或 iPhone 兼容性已经通过。
- 普通 Debug APK 不包含配件认证材料，可安装查看界面，但不能独立完成 iPhone 连接。
- 本分支尚未对 iPhone 17 Pro Max、iOS 27.2 RC、USB-C 或车机完成真机测试。见[真机验收记录](docs/IPHONE_17_IOS_27_2_RC.md)。

构建环境需要 JDK 25、Android SDK 37、NDK 28.2.13676358。命令与认证输入要求见 [构建指南](docs/BUILD.md)。源码保留 GPL-3.0、AGPL-3.0 及第三方许可；[上游中文 README](docs/UPSTREAM_README_0.2.21.zh-CN.md)仅作为历史资料，不代表本分支已经通过同样的测试。

**0.2.35** 新增无线音乐 150ms 档位、有线默认 100ms、增强缓冲音乐快速重启、有线视频等待上限、CarPlay 图标和版本递增检查。具体修改、安装与测试边界见[更新说明](docs/CARPLAY-0.2.35.md)。这些数值属于接收端缓冲/调度目标，端到端延迟仍需实车测量。
