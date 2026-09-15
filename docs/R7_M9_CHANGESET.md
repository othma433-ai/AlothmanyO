# R7-M9 Changeset — Accessibility Service Decomposition

**Baseline:** WA Al-Othmany Android v2.2.0 R7-M8  
**Milestone:** R7-M9  
**Scope:** Accessibility host decomposition only; no extraction, sync, join, persistence, or UI behavior redesign.

## What changed

1. Added the pure `AccessibilityHostCoordinator<S>` boundary.
   - Owns only attach/detach/event fan-out.
   - Has no WhatsApp navigation, sync, extraction, join, recovery, or persistence policy.
   - Is JVM-testable without Android.

2. Added `AndroidAccessibilityServiceHost`.
   - Converts Android `AccessibilityEvent` values into immutable `AccessibilityEventRecord` values.
   - Routes lifecycle to `AutomationRuntime`.
   - Routes event and scroll telemetry to `R7ShadowRuntime`.
   - Owns the Android-specific host glue that previously lived in `WaAccessibilityService`.

3. Reduced `WaAccessibilityService` to a thin Android entry point.
   - `onServiceConnected()` delegates to the host.
   - `onAccessibilityEvent()` delegates to the host.
   - `onDestroy()` delegates to the host.
   - It no longer references `AutomationRuntime`, `R7ShadowRuntime`, the event buffer, UI bridge, or automation engines directly.

4. Decoupled runtime and UI bridge from the concrete service class.
   - `AutomationRuntime.accessibility` now stores `AccessibilityService?` instead of `WaAccessibilityService?`.
   - `WhatsAppUiBridge` now accepts the Android `AccessibilityService` base type.
   - The bridge no longer reads `service.lastEventAt`; event timing comes from `AutomationRuntime.eventBuffer.lastEventAt()`.

5. Preserved prior R7 behavior.
   - Scroll-event telemetry is still emitted, now by `AndroidAccessibilityServiceHost`.
   - M3 smart-scroll behavior is unchanged.
   - M8 verified join flow is unchanged.
   - No Room schema change.
   - No manifest permission change.
   - No visual UI change.

## New verification gates

- `tools/run_r7_m9_tests.sh`
- `tools/r7_m9_contract.sh`
- `R7_M9_HOST_TYPECHECK_PASS`
- GitHub Actions R7-M9 test + compatibility gates before Android unit/lint/build jobs.

## Architectural result

The Android service is now a host rather than a workflow owner. Android lifecycle/event mechanics terminate at the host boundary; workflow policy remains in the existing automation/controllers.
