# WA Al-Othmany Android v2.0 Design

## Goal
Build a standalone Android companion automation app that operates the real WhatsApp UI through Android Accessibility, persists extraction state/results locally, and can continue long-running extraction jobs with bounded memory usage and verified state transitions.

## Scope v2.0
- Accessibility-first; no root required.
- WhatsApp Personal and WhatsApp Business package support, with pluggable package profiles for clones/work profiles where Android exposes them to the app.
- Group discovery/sync from the visible WhatsApp chat list.
- Deep extraction from group chats by scanning currently loaded message nodes, streaming URLs to persistent storage, scrolling toward older messages, and proving the oldest boundary before completion.
- New-only extraction that stops at a persisted per-group message/window fingerprint boundary.
- Persistent jobs, checkpoints, link deduplication, occurrence records, pause/resume/stop, recovery after process/service recreation.
- Foreground service for long-running automation.
- Export CSV/JSON/TXT and a lightweight XLSX export.
- Runtime diagnostics and explicit success/failure/partial states.

## Non-goals v2.0
- Direct access to WhatsApp private databases or encrypted storage.
- Bypassing group-admin approval, WhatsApp security checks, Android sandboxing, or rate limits.
- Root/Shizuku as a hard dependency.
- Fully autonomous joining/publishing in v2.0 core; architecture leaves extension points for a later release.

## Architecture
1. `WaAccessibilityService`: observes WhatsApp windows and exposes safe snapshots/actions.
2. `AutomationForegroundService`: owns long-running job lifecycle and notification controls.
3. `AutomationEngine`: deterministic state machine for sync/deep/new-only flows.
4. `WhatsAppUiBridge`: structural node scoring, chat identity verification, scrolling/click primitives, and post-action verification.
5. `ExtractionPipeline`: extracts/normalizes URLs, batches occurrences, and commits incrementally.
6. `AppDatabase` (Room): jobs, groups, links, occurrences, checkpoints, diagnostics.
7. `ExportManager`: streamed exports from Room to user-selected documents.
8. `RecoveryManager`: restores active/paused jobs and validates the current WhatsApp screen before resuming.

## Long-run stability constraints
- Room is the source of truth; RAM holds only bounded batches and small snapshots.
- Maximum in-memory occurrence batch: 128.
- UI preview limit: 500 link rows by default.
- Every UI action has a deadline and expected state transition; no unbounded await/loop.
- Every 25 scroll cycles: persistence flush + cooperative cooldown.
- Stall detection is based on unchanged window fingerprints plus no new persisted messages/URLs, not wall-clock alone.
- Completion requires: repeated oldest-boundary evidence, empty pending extraction batch, committed checkpoint, and verified current group identity.

## Deep extraction state flow
`PREPARE -> VERIFY_CHAT -> CAPTURE -> COMMIT -> SCROLL_OLDER -> VERIFY_PROGRESS -> CAPTURE ... -> VERIFY_OLDEST -> FINAL_FLUSH -> COMPLETE`

Failure paths resolve to `RECOVERING`, `PAUSED`, `PARTIAL`, or `FAILED`; they never silently become `COMPLETE`.

## Data model
- Job: id, mode, target package/profile, status, timestamps, counters.
- Group: stable local id, package/profile, display name, accessibility signature, lastSeen.
- Link: normalizedUrl unique, raw representative, domain, firstSeen, lastSeen, occurrenceCount.
- Occurrence: unique fingerprint, linkId, groupId, jobId, messageFingerprint, observedAt.
- Checkpoint: jobId/groupId, phase, scrollCount, lastWindowFingerprint, oldestEvidenceCount, lastNewOnlyBoundary, counters.
- DiagnosticEvent: severity, component, code, message, timestamp, contextJson.

## Safety / correctness
- The app interacts only with UI content exposed by Accessibility and user-granted storage/document destinations.
- It never claims success from a click alone; success requires a verified post-action state.
- It does not bypass approval-required group joins.
- Package allowlist defaults to official WhatsApp/Business and can be extended explicitly by the user.

## Testing
- Pure JVM tests for URL normalization, dedup fingerprints, reducer transitions, oldest-boundary detector, retry/deadline policy.
- Android build/manifest validation in GitHub Actions.
- Manual device acceptance test required for true WhatsApp UI behavior because Accessibility trees vary by WhatsApp/Android release.
