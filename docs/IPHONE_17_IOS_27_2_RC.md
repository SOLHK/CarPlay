# iPhone 17 Pro Max / iOS 27.2 RC acceptance

This branch has **no physical iPhone 17 Pro Max or iOS 27.2 RC result**. A successful Android build, USB charging indication, or decoder start is not a CarPlay connection result.

## Source review

| Layer | Current path | Result |
| --- | --- | --- |
| USB enumeration and permission | `IphoneUsbHost` discovers Apple USB vendor ID `0x05ac`, requests Android USB Host permission, and handles re-enumeration. `CarPlayController` chooses the wired path. | Code path present; device behavior **NOT TESTED**. It does not select by phone model name. |
| Wired session | `IphoneUsbHost` selects the CarPlay configuration and opens iAP2 after permission and re-enumeration. | Code path present; USB-C and this iPhone **NOT TESTED**. |
| Wireless session | `CarPlayController` uses the existing Bluetooth/Wi-Fi transport and reports startup errors. | Code path present; this iPhone and head unit **NOT TESTED**. |
| Audio and video | `CarPlayMediaKeys`, `ScreenStream`, and the existing Android media sinks implement metadata, audio and projection display. The receiver music page reads the current iPhone metadata and sends commands through the session controller. | Automated CI passed at `40d14f5e`; live sound and picture **NOT TESTED**. |
| Siri, calls, wheel keys | Existing microphone, call and wheel-key paths remain from DiPlay. | Vehicle-specific behavior **NOT TESTED**. |

No iOS 27.2 RC version branch or guessed USB product ID was added. If the phone fails, use a redacted diagnostic report to identify the first failing layer before changing transport code. The ordinary source-only Debug APK omits accessory authentication assets and cannot establish a standalone session.

## Physical test sequence

1. On the Android head unit, confirm USB Host/OTG and use a USB-C **data** cable and data-capable port. Note head-unit model, Android version, firmware, display resolution and app package/version.
2. Install a separately provisioned, authorized standalone test APK built with the runtime inputs described in [BUILD.md](BUILD.md). Do not put credentials or signing keys in this repository or in a diagnostic report.
3. With the iPhone unlocked, connect by USB, grant the Android USB permission, accept the iPhone trust and CarPlay prompts, and record whether enumeration, permission, iAP2, session, video and audio each succeed. Repeat after unplug/replug and lock/unlock.
4. Test wireless pairing, Wi-Fi handoff, reconnect, lock/unlock, and car network changes separately. Record the failure stage rather than assuming that Bluetooth pairing proves CarPlay.
5. During an active session, test Apple Music play/pause, skip, sustained audio, video, Siri microphone and response, an incoming call and music recovery. Test steering-wheel controls only on a car that exposes those inputs.
6. Use **Settings → Diagnostics → Save diagnostic report** after a failure. Current reports are saved under `Downloads/CarPlay` when Android permits it. Review and redact device identifiers, network names, credentials, private payloads and unrelated personal data before sharing. Do not upload automatically.

## Acceptance states

| Evidence | Status |
| --- | --- |
| Source path inspected | PASS |
| [Android CI at `40d14f5e`](https://github.com/SOLHK/CarPlay/actions/runs/39570837683): 3,101 unit tests, Lint, Debug APK builds | PASS |
| Robolectric receiver UI screenshots: home, settings, music, light/dark and compact landscape | PASS; screenshot pixels reviewed |
| Android emulator or head-unit UI interaction | NOT TESTED |
| Android head unit and USB Host | NOT TESTED |
| iPhone 17 Pro Max + iOS 27.2 RC wired USB-C | NOT TESTED |
| iPhone 17 Pro Max + iOS 27.2 RC wireless | NOT TESTED |
| Live audio, video, Siri, calls and wheel keys | NOT TESTED |
