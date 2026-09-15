# R7-M6 Changeset — Recovery, Watchdog, Lifecycle Reconstruction

## Scope
R7-M6 centralizes extraction recovery and makes pause/resume/stop durable at atomic boundaries without changing the existing UI contract.

## Added
- `r7/navigation/RecoveryController.kt`: bounded recovery ladder backed by `CircuitBreaker`.
- `r7/automation/Watchdog.kt`: event silence, repeated same-state, stale-window, gesture-timeout, service-recreation, and unexpected-package detection.
- `r7/accessibility/AccessibilityEventBuffer.kt`: bounded structural event history.
- `r7/automation/RunLifecycle.kt`: pause-request/atomic-pause/resume/stop generation semantics.
- `tools/run_r7_m6_tests.sh` and `tools/r7_m6_contract.sh`.

## Integrated
- Accessibility service feeds a bounded event buffer.
- Runtime assigns a new service generation on every accessibility-service attachment.
- Gesture fallback paths publish start/finish telemetry.
- Extraction pauses only after durable batch commit and writes a `PAUSED` checkpoint.
- Resume rebuilds execution from the immutable queue/checkpoint and re-verifies the WhatsApp conversation.
- Action jobs use the same atomic pause boundary semantics.
- Navigation and smart-scroll failures use the central recovery budget; exact package/title mismatch remains a hard rejection.
- A quarantined extraction item does not terminate later queue items.

## Non-goals
R7-M6 does not claim Android APK build verification or real-device recovery certification. Those remain CI/device gates.
