# CarPlay 0.2.38 — bounded PCM retries and recent diagnostics

Based on the 0.2.37 session-ownership repair (ffe3cec). The November 1 report still ran 0.2.36.

## Evidence and scope

The current session reported wireless music requested/start 150ms and a 170ms AudioTrack capacity.
One five-second statistics window recorded 2,174 zero-byte nonblocking writes. Zero writes on a full
track are legal; retrying them every 2ms generates unnecessary wakeups. Reception also had long gaps
and packet loss. These are distinct issues: this patch does not claim to recover missing wireless data.
The report says connectivity-scan pausing was unavailable because local ADB was off. Scanning is a
possible contributor, not a proven explanation for every gap. No ADB permission is requested or bypassed.

## Changes

- Consecutive full-track writes back off from 2 to 4 to at most 8ms, capped at one quarter of the
  measured queued PCM time. Empty/unknown queues and startup retain the existing prompt retry.
  Positive writes reset the backoff; shutdown remains interruptible. No PCM drops, silence insertion,
  faster playback, or increased buffering are introduced.
- `MediaAudioBuffer.kt` is unchanged, including wireless 150ms, wired 100ms, capacity/headroom and
  enhanced-buffering startup budgets.
- Exported event logs are filtered to the preceding three hours using absolute timestamps. Legacy
  logs use their session-start date and handle midnight; records with no recoverable date are omitted
  rather than attributed to today. Static device snapshots remain separate from the event window.
- New asynchronous logs include the full date and numeric timezone offset. Date formatting runs on
  the bounded writer, not on audio/video/UI producers. Redaction remains enforced before disk storage.
- A persistent, flushed writer removes per-line file open/close overhead. Rotation uses renames rather
  than cascading file copies; archives older than three hours are pruned periodically. The existing
  storage-size bound is retained, so available history can be shorter than three hours.

## Validation boundaries

New regression tests cover cutoff boundaries, missing dates, midnight, reordering, timezone offsets,
future/invalid timestamps, continuations, secret redaction, archive retention, PCM preservation and
bounded retries. Full unit tests, all three Lint tasks and all three Debug builds remain required.
CI results and final commit are authoritative; no head-unit or acoustic validation is claimed here.

The CI APK is source-only and does not contain the externally supplied accessory authentication assets
or the previous private installation-signing identity. It is not an approved in-car replacement APK.
