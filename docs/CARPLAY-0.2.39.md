# CarPlay 0.2.39 — fresh navigation and continuous audio input

Based on `a12ecf4de1fa6c99b5aed90596e48e85144fec83` (0.2.38), including the
0.2.37 session ownership and 0.2.38 diagnostic I/O / PCM retry repairs. The
previous `main` was still 0.2.36; these later repairs must remain in the update.

## Report and evidence boundary

The owner reports navigation remaining behind the vehicle, occasional USB audio
interruptions, and worse stutter over wireless. The supplied ten-second recording
does not show a synchronized real-world reference or phone display, so it cannot
measure location or end-to-end projection delay. No connected head unit is
available during this repair. Code regressions and device acceptance are separate.

## Repairs

### Location freshness

The upstream source replacement removed the earlier location ordering fix.
0.2.38 compares providers using `Location.time`, accepts fixes up to 30 seconds
old, replaces a provider's latest fix with any late callback, and receives updates
on the UI looper. Android documents `elapsedRealtimeNanos` as the appropriate
clock for ordering and age comparisons; wall time can change and differ between
providers. The repair restores monotonic freshness, prevents backward and
duplicate reports, and clears unavailable providers. NMEA retains the actual fix
time, including fractions of a second, and reports loss of a valid fix explicitly.
It does not extrapolate coordinates or claim to repair phone-side map matching.
This path is active only when the existing "report location to iPhone" option is
enabled and the phone requests location; the update does not enable it silently.

### Audio decoder backpressure

The existing compressed-audio path discards the current AAC/Opus packet when the
decoder has no input buffer within one 100 ms pump interval. A temporary stall can
therefore create a missing audible frame even when transport delivered the data.
Retain the pending input across bounded pump intervals and keep output draining,
playback maintenance, and shutdown responsive. Do not add silence, change playback
rate to hide loss, or increase the selected music startup buffer.

### Video latency

- A large, constant live input backlog can survive the old growth-only recovery
  test forever. Recover when a frame is at least 500 ms old, newer queued frames
  span at least 250 ms, and the backlog persists for 1.5 seconds without making
  at least 50 ms of catch-up progress. Existing hard limits, keyframe/reference
  safety, control boundaries, and protections for static tails and draining bursts
  remain. These are receiver policy thresholds, not measured device guarantees.
- Wireless clock mapping and smoothing now share one 200 ms presentation budget
  from arrival, as USB already shares one 100 ms budget. The previous wireless
  path could add up to another 100 ms of clock correction beyond its delay limit.
  Late decoded frames do not earn a new waiting period. This does not bound link
  delay, hardware decoding, or physical screen presentation.
- Five-second video statistics include `queueMax` to distinguish time waiting
  before decoding from codec latency and transport arrival gaps.

Music defaults remain 100 ms wired / 150 ms wireless. Application IDs, saved
preference keys, and the existing interface are preserved. Version codes increase
to mobile `0.2.39` / `46` and automotive `1.2.6` / `1206`.

## Verification required

Run the complete command in `AGENTS.md`, including all shared/common/home unit
tests, mobile/home/maphost Lint, and all three debug builds. Focused tests cover
late/out-of-order location data, invalidation and lifecycle, temporary audio input
backpressure, persistent video latency, catch-up bursts, and transport pacing.
CI and JUnit reports for the final source commit are the authority for the result.

A source-only CI APK cannot perform standalone CarPlay authentication. An owner
test APK must use the separately supplied existing runtime inputs and the same
installation signing identity; neither private input belongs in Git or public CI.

## Device acceptance

Install over the existing `com.solhk.carplay.hudtest` test application using its
original signing identity. Check USB and wireless separately: navigation position
at a known intersection, moving-map updates, continuous music, navigation prompts,
track switching, and disconnect/reconnect. A passenger can record a synchronized
comparison; export diagnostics immediately after any remaining issue. Verify the
new version in the diagnostic header. This document does not claim these device
checks have been completed.

## Primary references

- [Android Location elapsed realtime](https://developer.android.com/reference/android/location/Location#getElapsedRealtimeNanos())
- [Android MediaCodec output surface and timestamps](https://developer.android.com/reference/android/media/MediaCodec#releaseOutputBuffer(int,%20long))
