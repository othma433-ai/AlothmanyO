# WA Al-Othmany Android v2.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a persistent, accessibility-first Android automation core for long-running WhatsApp group link extraction.

**Architecture:** Accessibility events are converted into bounded immutable UI snapshots. A foreground-owned state machine issues verified UI actions and streams extracted URLs into Room in batches. Room checkpoints are the durable source of truth for crash/reload recovery and exports.

**Tech Stack:** Kotlin, Android SDK, AndroidX, Room, coroutines, XML views, Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-09-14-wa-al-othmany-android-v2-design.md`

## Global Constraints
- No root required.
- No direct WhatsApp private database access.
- No unbounded UI wait or loop.
- In-memory occurrence batch <= 128.
- Completion requires verified oldest-boundary evidence + final DB flush.
- Preserve a clean extension boundary for future Join/Publish engines.

---

### Task 1: Build skeleton and pure domain core
**Files:** Gradle files, manifest, `domain/*`, JVM test harness.
**Produces:** job states, URL normalizer, fingerprints, boundary detector.
- [ ] Write failing core tests.
- [ ] Run tests and observe failure.
- [ ] Implement minimum domain code.
- [ ] Run tests to green.

### Task 2: Persistent Room model and repositories
**Files:** `data/*`.
**Produces:** streamed upsert API, durable checkpoints, diagnostics.
- [ ] Add schema/entities/DAOs.
- [ ] Implement transactional `commitBatch(max 128)`.
- [ ] Add repository interfaces consumed by engine.

### Task 3: Accessibility snapshot/action bridge
**Files:** `accessibility/*`, service XML.
**Produces:** immutable snapshots, node scoring, verified scroll action.
- [ ] Implement bounded tree traversal.
- [ ] Implement message text extraction and chat identity fingerprinting.
- [ ] Implement scroll-backward + gesture fallback with deadlines.

### Task 4: Extraction state machine
**Files:** `automation/*`.
**Produces:** deep/new-only job orchestration and recovery checkpoints.
- [ ] Implement reducer/state transitions.
- [ ] Stream capture to DB before scrolling.
- [ ] Add oldest-boundary proof and partial/failure states.
- [ ] Add pause/resume/stop and periodic cooldown.

### Task 5: Foreground service and UI
**Files:** `service/*`, `ui/*`, resources.
**Produces:** user-controlled start/pause/resume/stop and status dashboard.
- [ ] Foreground notification channel/actions.
- [ ] Permission/accessibility status UI.
- [ ] Mode/package selection and live counters.

### Task 6: Export and recovery
**Files:** `export/*`, `recovery/*`.
**Produces:** CSV/JSON/TXT/XLSX streaming exports and startup recovery.
- [ ] Implement Storage Access Framework export.
- [ ] Restore unfinished jobs from Room on app/service restart.

### Task 7: Build automation and final verification
**Files:** `.github/workflows/android-debug.yml`, README.
- [ ] Add GitHub Actions Android debug build.
- [ ] Run local pure JVM tests.
- [ ] Run syntax/static source checks possible without Android SDK.
- [ ] Package full source ZIP and report remaining device-only validation.
