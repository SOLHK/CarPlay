# CarPlay

An independent Android head-unit CarPlay receiver under development by SOLHK. CarPlay 0.2.34 syncs the receiver and tests through [DiPlay main on 2026-10-24](https://github.com/shihabal3amri/DiPlay/tree/9d89633035d1add5b044ba3de5e57dd078bf2511), starting from [DiPlay 0.2.21](https://github.com/shihabal3amri/DiPlay/tree/6d3b8a8ce927cb4b115b8c3881f17619433e93b1); the previous SOLHK/CarPlay 0.2.33 source tree is not part of the current files. The receiver keeps DiPlay's connection core and settings persistence while its own interface is being rebuilt.

The app is **not made, certified, or endorsed by Apple**. The launcher uses a vector rendering of the green CarPlay mark; see [the icon notice](docs/THIRD_PARTY_NOTICES.md#carplay-icon). Apple CarPlay remains a trademark of Apple Inc. The projected interface is drawn by the iPhone, so Android UI changes affect only this receiver's own screens.

CarPlay **0.2.35** adds a 150 ms wireless music preset, 100 ms USB defaults, faster buffered-music restart, bounded USB video pacing, the CarPlay launcher mark, and a version-increment CI check. See [the update and validation notes](docs/CARPLAY-0.2.35.md). These are receiver buffer and scheduling targets; end-to-end car measurements remain required.

## Current development status

- Visible app name: CarPlay. Android application ID: `com.solhk.carplay`; debug builds use `com.solhk.carplay.hudtest`. Installing this package does not update an existing DiPlay or earlier SOLHK test app, and saved settings do not migrate automatically.
- Receiver home, settings, and music pages use shared light and dark tokens. The music page reads the active iPhone session's metadata and sends media commands through the existing controller; without a session, it shows no invented track and disables playback controls.
- [Android CI at `40d14f5e`](https://github.com/SOLHK/CarPlay/actions/runs/39570837683) passed unit tests, Lint, and source-only Debug APK builds. This does not establish head-unit or iPhone compatibility.
- The source-only debug build omits accessory authentication material. It can be installed to inspect the UI but is **not** a standalone iPhone connection package.
- iPhone 17 Pro Max, iOS 27.2 RC, USB-C, and on-car operation have not yet been tested on this branch. See [device acceptance](docs/IPHONE_17_IOS_27_2_RC.md).

## Build and validation

Use JDK 25, Android SDK 37, NDK 28.2.13676358, and the included Gradle wrapper:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

The source-only APK is `mobile/build/outputs/apk/debug/mobile-debug.apk`. A standalone car-test build requires the separate authorized runtime inputs described in [BUILD.md](docs/BUILD.md). No authentication keys or release-signing keys belong in Git.

## Source and licenses

The receiver derives from [DiPlay](https://github.com/shihabal3amri/DiPlay), [xcertplay](https://github.com/shilapi/xcertplay), and the DiAuto interface. Preserve GPL-3.0, AGPL-3.0, and third-party notices in [licenses](docs/licenses) and [third-party notices](docs/THIRD_PARTY_NOTICES.md). The [upstream README](docs/UPSTREAM_README_0.2.21.md) is kept as historical context; its release claims are not validation of this branch.
