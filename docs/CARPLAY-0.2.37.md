# CarPlay 0.2.37 — Session ownership and reconnection correctness

基线：0.2.36 `main` (2e9e178a794942ebbe2b8d3f20bcb5ad4fab0627)。

## 根因与修复

CarPlayMediaEngine 的多处音视频数据结构按 AirPlaySession 分组，但异常断开处理使用无条件的全局 clear()；与此同时 MediaSink 中 AudioRenderer 的身份只包含 (stream type, audioType)。旧连接正常的延迟断开或 TEARDOWN 可干扰新连接。

- `onFeedback(session)` 只返回当前控制连接所属的音频流，不混入其他 iPhone 的 sampleTime、streamConnectionID。
- `onSessionClosed` 只移除当前连接的 audioMeta、pendingMicrophone 和 audioCaptures，并保证异常断开后无残留 PCM renderer、音频焦点和旧麦克风；相同流身份仍被新连接占用时不停止新实例。
- 新音频流占用同一 (type, audioType) 后，退休旧连接的 UDP 接收器及其元数据；旧 RTP callback 不得重新创建设备输出。
- 旧连接的 screen onClosed / TEARDOWN 和音频 TEARDOWN 不得误清空新连接的 screen/audio sink。重复 screen SETUP 关闭旧 screen 不等于当前 session 断开。
- 过期连接发出的 unduckAudio 不得取消新连接的导航提示焦点。
- 补充双 session 与异常断开单元回归；未改变无线 150ms、有线 100ms 和导航原有软件缓冲预算。

## 验证

通过 GitHub PR 的 Android checks 验证全部单元测试、Lint、Debug 构建，以及版本和凭据检查。实车端到端延迟、车载系统版本差异仍需上车实测。

本次只更改源码与版本：主应用 `0.2.37` (`versionCode=44`)，AAOS `1.2.4` (`versionCode=1204`)。GitHub 的源码 Debug APK 不包含离线认证/原始签名材料，不能声称可替代之前签名的实车测试包。
