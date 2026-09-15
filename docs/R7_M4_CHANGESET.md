# R7-M4 Changeset — Durable Immutable Queue + Deep Extraction Persistence

## Scope

R7-M4 migrates extraction membership/order and visible-message persistence behind durable R7 repository boundaries while preserving the v2.2 UI, invite engine, publisher, M2 sync and M3 verified navigation/smart-scroll behavior.

## Added

- `ExtractionQueueItemEntity` and `ExtractionQueueDao`.
- Room migration `MIGRATION_3_4` with non-destructive `extraction_queue_items` creation.
- `ImmutableQueuePlanner` and explicit queue states: `PENDING`, `OPENING`, `VERIFYING`, `EXTRACTING`, `PAUSED`, `COMPLETED`, `FAILED`.
- `GroupRepository`, `LinkRepository`, `OperationRepository`, `CheckpointRepository`.
- `DeepExtractionStrategy` with deterministic conversation/context occurrence evidence.
- `ExtractionController.persistVerifiedViewport()` which commits bounded URL batches before saving the next checkpoint.
- Exact job counters recomputed from durable `job_links`/`occurrences` after persisted batches.
- Queue transition diagnostics and fail-closed transition handling.
- Pure Kotlin M4 tests, SQLite migration preservation test and M4 compatibility gate.

## Preserved compatibility

- Existing group/link/occurrence/checkpoint data remains in place during v3 -> v4 migration.
- Legacy occurrence IDs remain compatible because persisted observations keep the original visible message fingerprint.
- Existing `ExtractionRepository` remains as a backward-compatible facade while R7 production extraction uses focused repositories.
- No INTERNET permission, root/private WhatsApp database access or destructive Room migration is introduced.

## Deliberately deferred

- Evidence-based New-only high-water checkpoint redesign is R7-M5.
- Full pause/resume lifecycle reconstruction and watchdog behavior is R7-M6.
- Android build and real-device field acceptance remain authoritative later gates.
