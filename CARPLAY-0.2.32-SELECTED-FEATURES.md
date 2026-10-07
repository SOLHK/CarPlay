# CarPlay 0.2.32：移植 DiPlay 0.2.14 所选功能

基于你提供的 CarPlay 0.2.31，保留原有 Steam / 液态玻璃界面和音频卡顿修复。音乐默认缓冲为 **100 ms**；导航提示音继续使用原来的低延迟通道。

## 已加入的功能

| 功能 | 实际行为 / 设置位置 |
| --- | --- |
| 默认自动连接方式 | 设置 → 连接 → 默认连接模式，可选“上次使用”“无线”“USB”；与手动连接按钮独立保存。 |
| 桌面图标返回投屏 | 已有会话时，从桌面图标进入会返回 CarPlay 画面；带有明确设置页面的入口继续打开设置。 |
| USB 兼容读取 | 修复诊断帧分隔及尾部处理。大块读取被明确拒绝时，只重试一次 16 KiB；异常和超时不会触发不确定的重复提交。 |
| 蓝牙接收恢复及诊断 | 接收队列排空后唤醒接收线程，记录连接、EOF、超时、读取失败和缓存的配对 / 服务状态。 |
| 车机热点 IPv4 / IPv6 | 确认真实热点接口和稳定地址，优先使用可用 IPv4，保留带接口范围的 IPv6；双栈发布和连接探测使用对应的地址族。 |
| 无线切换时保留蓝牙控制 | 已显示画面而 Wi-Fi iAP2 控制通道未就绪时，保持蓝牙传送歌曲、导航及通话控制；通道就绪后才释放蓝牙。 |
| 无硬件加速窗口的视频输出 | 运行时自动切换为 SurfaceView，按实际窗口范围调整缩放和触摸坐标。 |
| 视频解密兼容 | 优先使用 Android 平台 ChaCha20-Poly1305，保留 Bouncy Castle 后备，并记录解密路径和耗时。 |
| 方向盘 Siri 按键 | 设置 → 音频 → 方向盘 Siri 按键：启用后点击分配按钮，再按车机按键；长按只启动一次 Siri。 |
| Siri 麦克风来源后备 | VOICE_RECOGNITION 被车机拒绝时，尝试 VOICE_COMMUNICATION；保留失败及选择结果的诊断。 |
| 音频焦点中断处理 | 设置 → 音频 → 暂时失去音频焦点时静音媒体。需同时启用音频焦点；恢复焦点后恢复媒体音量，保持通话和导航音轨。 |
| 比亚迪仪表通话及按键 | 设置 → 音频 → 比亚迪通话显示和方向盘控制。分别启用仪表通话卡片及实验性通话按键；接听 / 挂断发送到 iPhone。仪表通话卡片需要车机网络 ADB。 |
| DiLink 3 通话静音修复 | 显示通话状态时不再把厂商 CarPlay 音频状态切到会使通话静音的状态。 |
| 实验性通话回声消除 | 默认关闭；设置 → 音频 → 通话音频，打开后下次连接生效。SpeexDSP 原生库随 arm64-v8a、armeabi-v7a、x86_64 三种架构打包。 |
| 实验性通话声音过滤 | 默认关闭；仅处理通话音轨的低频部分，下次连接生效。 |

方向盘按键服务只接收按键，不读取屏幕内容。车机保留的按键以及后台 CarPlay 控制需要启用该无障碍服务；可通过系统无障碍设置或设置页的 ADB 操作启用。仪表通话显示、DiLink 3 通话按键和自定义 Siri 按键均默认关闭。

## 安装包

测试应用名称：CarPlay 0.2.32 Test。包名：`com.shihab.diplay.steam.hudtest`，版本码 51。沿用此前 100 ms 测试包的签名，可更新该测试包；与原正式应用使用不同包名。安装到 Android 9 或以上车机。

本次 APK 已包含此前确认的连接认证资产。源码包不包含认证私钥和 APK 签名私钥，构建时通过明确的外部目录输入认证资产。

## 构建及验证

使用项目 Gradle Wrapper、JDK 25、Android SDK 37 和 NDK 28.2.13676358。为 `local.properties` 指定本机 `sdk.dir`。普通源码构建：

```sh
./gradlew :mobile:assembleDebug
```

可独立连接的测试 APK：把已有的两份认证文件放在指定目录下的 `offline-mfi/identity.pk8` 和 `offline-mfi/certificate.p7b`，然后执行：

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

测试：

```sh
./gradlew --no-configuration-cache --init-script tools/steam-test-agent.gradle :shared:testDebugUnitTest :common:testDebugUnitTest
python3 tools/run-audio-jitter-checks.py --jars /path/to/kotlin-and-junit-jars
```

本次构建通过 1,044 项单元测试（shared 705 项、common 339 项）。APK 签名、16 KiB 打包对齐、认证资产一致性、三种架构的回声消除库，以及实际 DEX 中的 100 ms 默认值均已核验。验证结果见 `validation/0.2.32/`。单元测试和构建验证不能替代车机 / iPhone 实际连接测试；麦克风来源、方向盘键码、仪表通话接口和回声消除效果需要在你的车机验证。

上游来源：<https://github.com/shihabal3amri/DiPlay> 的 `v0.2.14`。移植包含公开源码中的配套测试与 SpeexDSP 许可文件。
