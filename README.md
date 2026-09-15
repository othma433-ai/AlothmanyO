# WA Al-Othmany Android v2.2.0

Standalone Android companion automation for WhatsApp. It operates the visible WhatsApp UI through Android Accessibility; it does **not** read WhatsApp private databases and does not require root.

## Implemented engines

### Persistent extraction engine
- WhatsApp Personal (`com.whatsapp`), Business (`com.whatsapp.w4b`) and explicit custom/clone package targets.
- Accessibility-based group filter navigation and bounded group synchronization for large group lists.
- Deep extraction with streaming URL commits, bounded memory, verified scroll progress and oldest-boundary proof.
- New-only extraction with persisted per-group anchors.
- Room 2.8.5 persistence: jobs, groups, links, job links, occurrences, checkpoints and diagnostics.
- Foreground service, pause/resume/stop, wake lock and `START_STICKY` recovery.
- CSV, JSON, TXT and lightweight XLSX streaming exports through Android's Storage Access Framework.

### Verified invite action engine — hardened in v2.2
- Accepts explicit `https://chat.whatsapp.com/...` invite URLs from the user.
- Adds **Scan only** mode that classifies invites without clicking Join or Request to Join.
- Classifies visible invite states as Direct, Approval-required, Community, Already-member, Request-sent, Invalid or Unknown.
- Click chain preserves the discovered target: same node -> clickable parent -> clickable child -> gesture center.
- A click is never treated as success by itself; the screen is re-read until a verified post-state appears.
- Approval-required groups send the normal WhatsApp join request only; no approval/security bypasses.
- Persistent action queue survives service/process recreation.

### Controlled publisher — duplicate-safe in v2.2
- Publishes only to group names explicitly entered by the user.
- Re-enters the Groups filter, opens the exact group and re-verifies group identity before touching the composer.
- Uses `ACTION_SET_TEXT`, verifies the exact message in the composer, clicks the discovered Send target, then verifies post-send UI acknowledgement.
- Persists a **send fence before the click**. If the process dies or the post-send state is ambiguous, recovery becomes verify-only and never auto-resends that item.
- Ambiguous sends move to `VERIFY_PENDING`; after bounded verification attempts they become `UNVERIFIED_NO_RESEND`, not a duplicate send.
- Bounded retries and pacing between targets; one failed group does not terminate the full queue.

### Recovery and diagnostics — expanded in v2.2
- Room schema v4 preserves v1/v2/v3 data and adds durable action phases, result states, evidence, verification-attempt counts and progress heartbeats.
- Recovery selects the newest unfinished extraction or action job.
- Latest diagnostics are surfaced on the main screen.
- Scan/Join/Publish item results can be exported as a paged CSV audit report.
- Action jobs finish as `COMPLETE`, `PARTIAL`, `STOPPED`, `FAILED` or `RECOVERING`; unresolved items are never silently reported as successful.

## R7 migration status

This source includes **R7-M1 through R7-M10**. M1-M9 implement the runtime architecture migration: immutable snapshots, verified synchronization/navigation/scrolling, durable extraction queue, evidence-based New-only, centralized recovery, versioned profiles/replay, exact-target Join, and a thin Accessibility host. **M10 is the build/certification gate**: pinned Android toolchain, APK artifact verification, checksums, connected-device smoke capture, and an explicit WhatsApp field-results matrix.

Current evidence label for this package is **R7-M10 SOURCE/CERTIFICATION GATE VERIFIED / ANDROID BUILD VERIFICATION PENDING / REAL-DEVICE FIELD VERIFICATION PENDING**. Android build status may only be upgraded after the GitHub Actions M10 workflow produces and verifies the APK artifacts. Production-candidate status additionally requires all device field gates in `docs/R7_M10_FIELD_RESULTS_TEMPLATE.csv` to pass with evidence. See `docs/R7_M10_CERTIFICATION.md`.

## Build

The repository intentionally does not vendor Android SDK or Gradle binaries. Android Studio can import it directly, or GitHub Actions can build it with `.github/workflows/android-debug.yml`.

Required build toolchain:

- JDK 17
- Android SDK 35 / Build Tools 35.0.0
- Gradle 8.11.1
- Android Gradle Plugin 8.9.2
- Kotlin Gradle Plugin 2.2.21
- Room 2.8.5

Run the pure Kotlin tests locally even without Android SDK:

```bash
./tools/run_core_tests.sh
./tools/run_r7_m1_tests.sh
./tools/run_r7_m2_tests.sh
./tools/run_r7_m3_tests.sh
./tools/run_r7_m4_tests.sh
./tools/run_r7_m5_tests.sh
./tools/run_r7_m6_tests.sh
./tools/run_r7_m7_tests.sh
./tools/run_r7_m8_tests.sh
./tools/run_r7_m9_tests.sh
python tools/test_r7_m4_migration.py
bash tools/r7_m2_contract.sh
bash tools/r7_m3_contract.sh
bash tools/r7_m4_contract.sh
bash tools/r7_m5_contract.sh
bash tools/r7_m6_contract.sh
bash tools/r7_m7_contract.sh
bash tools/r7_m8_contract.sh
bash tools/r7_m9_contract.sh
bash tools/r7_m10_contract.sh
python tools/verify_project.py
```

## Device setup

1. Install the APK.
2. Open **Accessibility settings** from the app and enable **WA Al-Othmany**.
3. Select WhatsApp Personal, Business or a custom/clone package.
4. For extraction, run **Sync Groups** first, then Deep or New-only.
5. For invite work, paste one WhatsApp invite link per line and choose **Scan only** or **Join**.
6. For Publish, enter only the intended group names plus the message.
7. Keep the device unlocked and WhatsApp available while visible UI automation is running.

## Correctness rules

- No unbounded UI wait: searches, waits, scrolls and retries have explicit limits or timeouts.
- URL occurrence batches are capped at 128 entries.
- An extraction group is only `COMPLETE` after boundary evidence and a final identity re-check.
- A join/publish action is only `SUCCESS` after a verified post-action state.
- Extraction queue membership/order is frozen when a run begins; later synchronization cannot mutate the active queue.
- Re-running/recovering extraction is idempotent at occurrence level.
- Join recovery naturally recognizes already-member/request-sent states.
- Publish recovery uses `SEND_CLICKED` plus visible-message verification to reduce duplicate sends after process interruption.

## Important platform limits

Android Accessibility exposes only what WhatsApp exposes in its current UI tree. WhatsApp UI updates can require selector/heuristic updates. Android profile isolation also matters: Secure Folder / Work Profile / cloned-app environments may require installing/enabling the companion service inside that profile; an Accessibility service in one profile cannot bypass another profile's sandbox.

This app does not bypass approval-required joins, WhatsApp security checks, Android sandboxing, platform rate limits or account restrictions.
