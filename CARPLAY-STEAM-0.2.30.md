# CarPlay 0.2.30 · Steam

本版重做应用自己的首页、连接设置、等待 / 错误页、设置子页面与弹窗，提供正式签名 APK 和对应完整源码。Android 原生绘制参考 Liquid Glass 的通透层次、圆角和边缘高光；未使用上游首页结构、宣传文案或绿色 C 图标。

## 界面调整

- 首页改为一个主连接面板与三个等宽快捷入口：连接方式、iPhone、USB。有独立绘制的手机 / 车机图形，设置入口放在顶部。窄屏纵向排列，车机返回键缩短显示文字以保持与设置键同高。
- 连接设置按“方式、配对、连接”排列；宽屏下后两个步骤并排。Wi-Fi Direct 与车机热点显示各自的要求，Android 9 隐藏无法使用的 Wi-Fi Direct 选项。
- 等待页和错误页采用独立玻璃工作区，显示当前连接方式、真实阶段、原因和对应恢复操作。窄屏 / 大字体时内容可滚动。
- 主设置页保留独立的分类导航，子页面、选择框、权限 / 手机 / 热点弹窗使用相同材质；语言弹窗也统一样式。应用图标换为独立的车机 / 手机矢量图形。
- UI 使用静态缓存的渐变与原生触摸反馈，不捕捉视频帧做背景模糊，也没有持续刷新玻璃的动画循环。同样的首页状态文本不重复设置，热点状态每次刷新只检查一次。

## 连接修正

1. 0.2.29 在没有保存无线模式时默认 MANUAL，并将这个选择写入设置。没有车机热点信息或热点没有打开时，这个模式无法建立连接。0.2.30 在 Android 10+ 的未配置情况下默认 Wi-Fi Direct，Android 9 继续使用支持的车机热点；已保存的手动选择及热点信息保持不变。
2. 等待页原先先翻译状态，再根据英文关键词判断显示内容。中文提示可能落回“正在准备”。本版直接处理 CarPlayStatus，并仅用原始失败原因区分恢复操作。
3. Wi-Fi 未开启、权限缺失、手动频道不可用、外部 Wi-Fi Direct 组、驱动拒绝全部创建尝试或热点信息错误，均显示对应入口并暂停无效自动重建。临时传输中断继续沿用指数退避重连。
4. 已排队的重连在发现需要用户处理的错误时也会退出。恢复自动频道入口保存 Auto，再通过现有的停止 / 重建流程连接；清理外部 Wi-Fi Direct 组仍经过原确认流程。
5. 后台保留失败会话时，首页同步失败原因并显示“重新连接”；按钮会实际停止旧会话再启动，而不是只打开原错误页。反馈受会话持有者限制，旧持有者不能覆盖当前反馈；结束会话时清除。

## 用户截图的判断与操作

- 0.2.25 照片显示 Wi-Fi Direct 已连接；0.2.29 照片显示车机热点模式且热点未开启，两张使用的是不同无线模式。截图无法判断该模式是主动选择、首次默认还是重新安装后的设置。
- 升级保留已经保存的 MANUAL 模式。可以从新首页“连接方式”切回 Wi-Fi Direct，首选频道设为自动，保持车机 Wi-Fi、蓝牙和 iPhone Wi-Fi 开启，再选择 iPhone 连接。
- 另一张等待画面对应车机拒绝 Wi-Fi Direct 创建请求。单凭照片不能确认是驱动、占用、权限还是频道问题，也不能证明 iPhone 或认证文件有问题。新错误页给出定向操作；持续失败可试车机热点或 USB，并从“设置 → 工具”导出诊断报告。
- 车机热点模式需要在车机设置中打开热点，并保存匹配的名称、密码和安全类型；应用不会静默替换现有热点或自动清理其他投屏应用的网络。

Android 官方 Wi-Fi Direct 文档：<https://developer.android.com/develop/connectivity/wifi/wifi-direct>。Apple Liquid Glass 设计参考：<https://developer.apple.com/documentation/technologyoverviews/liquid-glass>。

## 验证记录

- release 编译及独立认证输入检查通过。Android 单元测试共 808 项通过（common 301、shared 507），失败、错误、跳过均为 0。
- release lint 0 个错误、4 个警告，保留既有 TrustAllX509TrustManager 等警告记录；未声称完成安全审计。
- 9 张 Android View 原生图形预览已人工检查。
- APK 包名、版本、ZIP CRC、16KiB ZIP 对齐、签名与新图标引用检查通过；签名、两个认证输入和 6 个项目原生库与 0.2.27 一致。安装包已确认包含新首页 / 等待 / 恢复 UI 与后台失败反馈代码。

本版的完整结果在 `validation/unit-tests.json`、`validation/android-build-checks-0.2.30.json` 和 `validation/apk-verification-0.2.30.json`。原始测试 / lint 报告放在 `validation/android-0.2.30`。

布局测试使用 Robolectric 4.17 NATIVE 图形模式绘制实际 Android View。`validation/ui-0.2.30` 包含首页宽窄屏、连接设置、等待 / 错误页、大字体错误页、设置、扩展与关于的九张预览；这些是测试渲染，不是真机截图。测试补入版本号用于预览，未伪造连接成功状态。

未进行车机安装、iPhone 实际握手、连续无线 / USB 播放或真机帧率测试。不能把单元测试通过视为实车连通或 60fps 保证。旧版报告与上游历史实车说明保留为归档。

## 构建

包名 `com.shihab.diplay.steam`，版本 `0.2.30`，versionCode `49`，最低 Android 9。使用原签名；正式 APK 认证文件与 0.2.27 的选定输入相同。源码包不包含签名密钥或运行认证文件，外部输入方式见 `docs/BUILD.md`。

```sh
./gradlew :common:testDebugUnitTest :shared:testDebugUnitTest :mobile:lintRelease :mobile:verifyStandaloneAuthentication :mobile:assembleRelease
```
