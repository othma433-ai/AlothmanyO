# WA Workspace Android R7 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate WA Workspace Android v1.18.5 R6 into the R7 verified, event-driven automation engine without changing the existing UI/bridge contract and without claiming Production Ready before build and real-device gates pass.

**Architecture:** Preserve R6 as the behavior baseline and migrate incrementally. First create immutable accessibility snapshots, stable observations, adapter/action contracts, generation guards, traces, metrics, and tests behind existing behavior. Then migrate synchronization, navigation/scrolling, extraction, recovery, profiles/replay, and finally join/publish. Every state transition follows `Action -> Observe -> Verify -> Commit State -> Continue`.

**Tech Stack:** Android, Java 17, compileSdk 36, targetSdk 36, minSdk 26, Android Gradle Plugin 8.13.2, Gradle 8.13, SQLite/WAL, Android AccessibilityService, local WebView/JavaScript bridge, JUnit JVM tests, Android lint, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-15-wa-workspace-r7-architecture-freeze-v1.0.md` (source specification: `2026-09-15-WA-Workspace-R7-ARCHITECTURE-FREEZE-v1.0.md`)

## Global Constraints

- Existing R6 UI and native bridge command behavior remains backward-compatible for unchanged commands.
- `popup.html`, `popup.css`, icons, and the 38-control interaction model remain unchanged unless a diagnostic-only control is explicitly required.
- No INTERNET permission may be added.
- No WhatsApp private database access, root-only access, encryption bypass, or server-side delivery claims.
- Raw `AccessibilityNodeInfo` must never survive an asynchronous engine transition.
- Every state-changing async callback is guarded by `runId`, `stateGeneration`, and `actionId`.
- `ScreenClassifier` and `ScreenStabilityDetector` remain separate concerns.
- A successful Android action dispatch is not a successful workflow transition until its postcondition is verified.
- Active extraction queue membership/order is immutable after the operation begins.
- Persistence is incremental and idempotent before the next risky UI action.
- Recovery is circuit-bounded; one failed group must not terminate later healthy groups.
- Build environment: JDK 17, Gradle 8.13, compileSdk 36.
- Release labels must remain evidence-based: source/build/device status are reported separately.

---

## Target File Structure

The R7 source root is:

`app/src/main/java/com/waworkspace/android/`

New production files:

```text
accessibility/
  AccessibilityGateway.java
  AccessibilityEventBuffer.java
  LiveNodeResolver.java
  ResolutionResult.java
snapshot/
  NodeSnapshot.java
  NodeSnapshotFactory.java
  SnapshotSignature.java
  ActionTargetEvidence.java
automation/
  AutomationState.java
  AutomationEngine.java
  ActionContext.java
  ActionRequest.java
  ActionResult.java
  VerificationResult.java
  ActionExecutor.java
  BreakerKey.java
  CircuitDecision.java
  CircuitBreaker.java
  AutomationFacade.java
adapter/
  AdapterMatch.java
  WhatsAppAdapter.java
  ConsumerWhatsAppAdapter.java
  BusinessWhatsAppAdapter.java
  GenericWhatsAppAdapter.java
observe/
  ScreenType.java
  RiskLevel.java
  StabilityResult.java
  ScreenObservation.java
  ScreenClassifier.java
  ScreenStabilityDetector.java
sync/
  SyncController.java
  GroupGrabberController.java
navigation/
  ConversationNavigator.java
  SmartScrollController.java
  RecoveryController.java
extraction/
  ConversationVerifier.java
  ExtractionController.java
  DeepExtractionStrategy.java
  NewOnlyExtractionStrategy.java
persistence/
  GroupRepository.java
  LinkRepository.java
  OperationRepository.java
  CheckpointRepository.java
  DiagnosticRepository.java
  MetricRepository.java
profile/
  RuntimeProfileKey.java
  RuntimeProfile.java
  RuntimeProfileRepository.java
diagnostics/
  TraceEvent.java
  TraceRecorder.java
  RuntimeMetricSample.java
  MetricRecorder.java
  DiagnosticCapture.java
  ReplayFixture.java
join/
  JoinController.java
  InviteClassifier.java
  JoinTargetResolver.java
  JoinResultVerifier.java
```

Existing files intentionally preserved and modified incrementally:

```text
app/src/main/java/com/waworkspace/android/accessibility/ExtractionAccessibilityService.java
app/src/main/java/com/waworkspace/android/persistence/WorkspaceDb.java
app/src/main/assets/popup.html
app/src/main/assets/css/popup.css
app/src/main/assets/js/android-ui.js
app/src/main/AndroidManifest.xml
app/build.gradle
.github/workflows/android-ci.yml
```

Test root:

`app/src/test/java/com/waworkspace/android/`

---

# Phase R7-M1 — Foundation Contracts

### Task 1: Freeze R6 Compatibility Baseline

**Files:**
- Create: `app/src/test/java/com/waworkspace/android/compat/R6CompatibilityContractTest.java`
- Create: `tools/r7_m1_contract.sh`
- Modify: `.github/workflows/android-ci.yml`
- Read-only baseline: `app/src/main/assets/popup.html`
- Read-only baseline: `app/src/main/assets/css/popup.css`
- Read-only baseline: `app/src/main/assets/js/android-ui.js`

**Interfaces:**
- Consumes: current R6 assets, manifest, bridge command names, build metadata.
- Produces: a repeatable compatibility gate named `R7_M1_COMPAT_PASS`.

- [ ] **Step 1: Write the failing compatibility test**

Create assertions that the unchanged bridge/UI commands remain present: `sync`, `extract`, `scan`, `join`, `publish`, `pause`, `resume`, `stop`, `retryFailed`, export actions, and that the project manifest does not declare INTERNET permission.

- [ ] **Step 2: Run the focused test**

Run:

```bash
./gradlew testDebugUnitTest --tests '*R6CompatibilityContractTest'
```

Expected before wiring: test fails if the new R7 contract fixture is not yet connected.

- [ ] **Step 3: Add a shell contract gate**

`tools/r7_m1_contract.sh` must fail on:

```bash
grep -Rqs 'android.permission.INTERNET' app/src/main/AndroidManifest.xml
grep -Rqs '/data/data/com.whatsapp' app/src/main/java
grep -Rqs 'msgstore.db' app/src/main/java
```

and require the R7 foundation classes created by later M1 tasks.

- [ ] **Step 4: Add CI invocation**

Run `bash tools/r7_m1_contract.sh` before `lintDebug` and APK assembly.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/waworkspace/android/compat/R6CompatibilityContractTest.java tools/r7_m1_contract.sh .github/workflows/android-ci.yml
git commit -m "test(r7): freeze R6 compatibility contract"
```

### Task 2: Immutable NodeSnapshot Boundary

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/snapshot/NodeSnapshot.java`
- Create: `app/src/main/java/com/waworkspace/android/snapshot/SnapshotSignature.java`
- Create: `app/src/test/java/com/waworkspace/android/snapshot/NodeSnapshotTest.java`

**Interfaces:**
- Produces: `NodeSnapshot`, immutable value object; `SnapshotSignature.compute(NodeSnapshot)`.
- Consumed later by: classifiers, adapters, replay fixtures, action target evidence.

- [ ] **Step 1: Write immutable-value tests**

Verify constructor values cannot be mutated after construction and equality/signature remain deterministic for identical sanitized structural input.

- [ ] **Step 2: Implement `NodeSnapshot`**

Use final fields for:

```java
String viewId;
String className;
String textHash;
String contentDescriptionHash;
int left;
int top;
int right;
int bottom;
boolean clickable;
boolean scrollable;
boolean selected;
boolean enabled;
boolean visibleToUser;
int depth;
String parentSignature;
int childCount;
String packageName;
int windowId;
long capturedAt;
```

Do not store raw `CharSequence` message bodies by default.

- [ ] **Step 3: Implement deterministic signature**

`SnapshotSignature.compute` hashes structural fields plus redacted/hash text fields, never raw sensitive message text.

- [ ] **Step 4: Run tests**

```bash
./gradlew testDebugUnitTest --tests '*NodeSnapshotTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waworkspace/android/snapshot app/src/test/java/com/waworkspace/android/snapshot
git commit -m "feat(r7): add immutable accessibility snapshots"
```

### Task 3: Accessibility Gateway and Live-Node Reacquisition

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/accessibility/AccessibilityGateway.java`
- Create: `app/src/main/java/com/waworkspace/android/accessibility/LiveNodeResolver.java`
- Create: `app/src/main/java/com/waworkspace/android/accessibility/ResolutionResult.java`
- Create: `app/src/main/java/com/waworkspace/android/snapshot/NodeSnapshotFactory.java`
- Create: `app/src/main/java/com/waworkspace/android/snapshot/ActionTargetEvidence.java`
- Create: `app/src/test/java/com/waworkspace/android/accessibility/LiveNodeResolverPolicyTest.java`
- Modify: `app/src/main/java/com/waworkspace/android/accessibility/ExtractionAccessibilityService.java`

**Interfaces:**
- `AccessibilityGateway.captureRoot(): NodeSnapshot`
- `AccessibilityGateway.captureVisibleTree(): List<NodeSnapshot>`
- `ActionTargetEvidence` contains package/window/signature/bounds/viewId/class evidence only.
- `ResolutionResult.Status` is `MATCH`, `NOT_FOUND`, `AMBIGUOUS`, or `WINDOW_MISMATCH`.
- `LiveNodeResolver.resolve(ActionTargetEvidence): ResolutionResult`
- The engine never receives `AccessibilityNodeInfo`.

- [ ] **Step 1: Write policy tests**

Test resolution rejection for mismatched package, window ID, structural signature, and stale timestamp evidence.

- [ ] **Step 2: Implement snapshot factory**

Convert live nodes synchronously to immutable snapshots and release/recycle local references according to Android API semantics.

- [ ] **Step 3: Implement resolver contract**

Reacquire a live node immediately before dispatch by matching current root against target evidence. Return `NOT_FOUND`, `AMBIGUOUS`, `WINDOW_MISMATCH`, or `MATCH`.

- [ ] **Step 4: Wire service behind gateway without behavior change**

Keep all existing R6 workflow decisions intact. Only route snapshot capture/action-node lookup through the gateway where possible.

- [ ] **Step 5: Run compatibility + JVM tests**

```bash
./gradlew testDebugUnitTest
bash tools/r7_m1_contract.sh
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/waworkspace/android/accessibility app/src/main/java/com/waworkspace/android/snapshot app/src/test/java/com/waworkspace/android/accessibility
git commit -m "feat(r7): isolate live accessibility nodes behind gateway"
```

### Task 4: Screen Observation and Stability

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/observe/ScreenType.java`
- Create: `app/src/main/java/com/waworkspace/android/observe/RiskLevel.java`
- Create: `app/src/main/java/com/waworkspace/android/observe/StabilityResult.java`
- Create: `app/src/main/java/com/waworkspace/android/observe/ScreenObservation.java`
- Create: `app/src/main/java/com/waworkspace/android/observe/ScreenClassifier.java`
- Create: `app/src/main/java/com/waworkspace/android/observe/ScreenStabilityDetector.java`
- Create: `app/src/test/java/com/waworkspace/android/observe/ScreenStabilityDetectorTest.java`

**Interfaces:**
- `ScreenClassifier.classify(List<NodeSnapshot>): ScreenObservation`
- `ScreenStabilityDetector.accept(ScreenObservation, RiskLevel): StabilityResult`
- `RiskLevel`: `NORMAL`, `HIGH`.
- `StabilityResult.Status`: `COLLECTING`, `STABLE`, `RESET`, `UNSTABLE_TIMEOUT`.

- [ ] **Step 1: Write stability tests**

Cover:

```text
NORMAL: 2 compatible observations -> STABLE
HIGH: 3 compatible observations -> STABLE
contradicting screen -> counter reset
package/window mismatch -> counter reset
insufficient observations before deadline -> UNSTABLE_TIMEOUT
```

- [ ] **Step 2: Implement immutable observation**

Fields:

```java
ScreenType type;
double confidence;
List<String> evidence;
String packageName;
int windowId;
String viewportSignature;
long observedAt;
```

- [ ] **Step 3: Implement stability detector**

Do not perform Android actions. It only evaluates ordered observations.

- [ ] **Step 4: Run tests**

```bash
./gradlew testDebugUnitTest --tests '*ScreenStabilityDetectorTest'
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waworkspace/android/observe app/src/test/java/com/waworkspace/android/observe
git commit -m "feat(r7): separate screen classification from stability"
```

### Task 5: WhatsApp Adapter Contracts

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/adapter/AdapterMatch.java`
- Create: `app/src/main/java/com/waworkspace/android/adapter/WhatsAppAdapter.java`
- Create: `app/src/main/java/com/waworkspace/android/adapter/ConsumerWhatsAppAdapter.java`
- Create: `app/src/main/java/com/waworkspace/android/adapter/BusinessWhatsAppAdapter.java`
- Create: `app/src/main/java/com/waworkspace/android/adapter/GenericWhatsAppAdapter.java`
- Create: `app/src/test/java/com/waworkspace/android/adapter/WhatsAppAdapterTest.java`

**Interfaces:**

`AdapterMatch` contains `boolean exact`, `double confidence`, and `String reason`.

```java
String packageName();
AdapterMatch matchPackage(String packageName);
ScreenObservation classify(List<NodeSnapshot> nodes);
Optional<ActionTargetEvidence> findGroupsFilter(List<NodeSnapshot> nodes);
Optional<ActionTargetEvidence> findConversationHeader(List<NodeSnapshot> nodes);
Optional<ActionTargetEvidence> findMessageList(List<NodeSnapshot> nodes);
Optional<ActionTargetEvidence> findUnreadBoundary(List<NodeSnapshot> nodes);
Optional<ActionTargetEvidence> findJoinAction(List<NodeSnapshot> nodes);
```

- [ ] **Step 1: Write package/adaptation tests**

Verify consumer matches `com.whatsapp`, business matches `com.whatsapp.w4b`, generic never outranks an exact adapter.

- [ ] **Step 2: Move only selector/label knowledge into adapters**

No Sync/Extraction transition logic enters adapters.

- [ ] **Step 3: Add Arabic/English semantic label sets from R6**

Retain current R6 labels exactly as compatibility data, not as workflow logic.

- [ ] **Step 4: Run adapter + compatibility tests**

```bash
./gradlew testDebugUnitTest --tests '*WhatsAppAdapterTest' --tests '*R6CompatibilityContractTest'
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waworkspace/android/adapter app/src/test/java/com/waworkspace/android/adapter
git commit -m "feat(r7): isolate WhatsApp UI knowledge behind adapters"
```

### Task 6: Action Identity, Generation Guard, and Verification Results

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/automation/AutomationState.java`
- Create: `app/src/main/java/com/waworkspace/android/automation/AutomationEngine.java`
- Create: `app/src/main/java/com/waworkspace/android/automation/ActionContext.java`
- Create: `app/src/main/java/com/waworkspace/android/automation/ActionRequest.java`
- Create: `app/src/main/java/com/waworkspace/android/automation/ActionResult.java`
- Create: `app/src/main/java/com/waworkspace/android/automation/VerificationResult.java`
- Create: `app/src/main/java/com/waworkspace/android/automation/ActionExecutor.java`
- Create: `app/src/test/java/com/waworkspace/android/automation/ActionExecutorGenerationTest.java`

**Interfaces:**

```java
ActionContext(runId, operationId, queueItemId, stateGeneration, actionId, attemptNo, requestedAt)
boolean ActionExecutor.isCurrent(ActionContext context)
ActionResult ActionExecutor.dispatch(ActionRequest request)
void ActionExecutor.onVerification(ActionContext context, VerificationResult result)
```

- [ ] **Step 1: Write stale-callback tests**

Prove that an old generation, wrong run ID, or no-longer-pending action ID cannot mutate state.

- [ ] **Step 2: Implement central pending-action registry**

Store only bounded metadata, not live nodes.

- [ ] **Step 3: Implement dispatch semantics**

`DISPATCHED` means only API acceptance. Workflow state advances only after `VerificationResult.PASS`.

- [ ] **Step 4: Run tests**

```bash
./gradlew testDebugUnitTest --tests '*ActionExecutorGenerationTest'
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waworkspace/android/automation app/src/test/java/com/waworkspace/android/automation
git commit -m "feat(r7): guard actions by run generation and verification"
```

### Task 7: Trace and Runtime Metric Foundation

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/diagnostics/TraceEvent.java`
- Create: `app/src/main/java/com/waworkspace/android/diagnostics/TraceRecorder.java`
- Create: `app/src/main/java/com/waworkspace/android/diagnostics/RuntimeMetricSample.java`
- Create: `app/src/main/java/com/waworkspace/android/diagnostics/MetricRecorder.java`
- Create: `app/src/main/java/com/waworkspace/android/persistence/DiagnosticRepository.java`
- Create: `app/src/main/java/com/waworkspace/android/persistence/MetricRepository.java`
- Create: `app/src/test/java/com/waworkspace/android/diagnostics/RetentionPolicyTest.java`

**Interfaces:**
- `TraceRecorder.record(TraceEvent)`
- `MetricRecorder.sample(RuntimeMetricSample)`
- `DiagnosticRepository.appendSummary(TraceEvent)` and `MetricRepository.appendSample(RuntimeMetricSample)` persist bounded summaries through `WorkspaceDb`.
- bounded in-memory ring buffer + durable summary hooks.

- [ ] **Step 1: Write retention tests**

Prove count/age/size boundaries evict old detail records and preserve aggregate summaries.

- [ ] **Step 2: Implement trace schema**

Include run ID, operation ID, queue item ID, state, action ID, before/after observation signatures, verification result, recovery reason, adapter/profile identity.

- [ ] **Step 3: Implement metric sampling**

Include PSS when available, events/min, snapshots/min, actions/min, verification failures, recoveries, fallback count, DB batches, state latency, stale callbacks, circuit-breaker activations.

- [ ] **Step 4: Ensure no message bodies are stored**

Tests inspect serialized trace/metric output for raw test-message sentinel leakage.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waworkspace/android/diagnostics app/src/test/java/com/waworkspace/android/diagnostics
git commit -m "feat(r7): add bounded traces and runtime metrics"
```

### Task 8: Circuit Breaker Foundation

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/automation/BreakerKey.java`
- Create: `app/src/main/java/com/waworkspace/android/automation/CircuitDecision.java`
- Create: `app/src/main/java/com/waworkspace/android/automation/CircuitBreaker.java`
- Create: `app/src/test/java/com/waworkspace/android/automation/CircuitBreakerTest.java`

**Interfaces:**

```java
CircuitDecision recordFailure(BreakerKey key);
void recordVerifiedProgress(BreakerKey key);
```

`CircuitDecision` is `RETRY`, `ALTERNATE_STRATEGY`, `QUARANTINE_ITEM`, or `DEGRADE_RUN`.

Thresholds:

```text
3 same-transition failures -> ALTERNATE_STRATEGY
5 same-transition failures -> QUARANTINE_ITEM
run-wide critical threshold -> DEGRADE_RUN
```

- [ ] **Step 1: Write threshold/decay tests**
- [ ] **Step 2: Implement breaker key with operation/item/state/category/adapter-profile identity**
- [ ] **Step 3: Integrate only diagnostic reporting in M1; do not alter R6 flow yet**
- [ ] **Step 4: Run full M1 suite**

```bash
./gradlew testDebugUnitTest
bash tools/r7_m1_contract.sh
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waworkspace/android/automation/CircuitBreaker.java app/src/test/java/com/waworkspace/android/automation/CircuitBreakerTest.java
git commit -m "feat(r7): add bounded recovery circuit breaker"
```

### Task 9: M1 Integration Gate

**Files:**
- Modify: `app/src/main/java/com/waworkspace/android/accessibility/ExtractionAccessibilityService.java`
- Modify: `.github/workflows/android-ci.yml`
- Create: `docs/R7_M1_VERIFICATION.md`

**Interfaces:**
- Existing R6 behavior remains authoritative.
- M1 foundation is observable but does not replace Sync/Extraction decision paths.

- [ ] **Step 1: Wire observation/trace hooks around existing R6 behavior**
- [ ] **Step 2: Verify no new UI bridge commands were introduced or removed**
- [ ] **Step 3: Run authoritative gates**

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew assembleRelease
bash tools/r7_m1_contract.sh
node --check app/src/main/assets/js/android-ui.js
```

- [ ] **Step 4: Record status precisely**

If all build gates pass but no device test occurred, status must be:

`R7-M1 BUILD VERIFIED / FIELD VERIFICATION PENDING`

not Production Ready.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waworkspace/android/accessibility/ExtractionAccessibilityService.java .github/workflows/android-ci.yml docs/R7_M1_VERIFICATION.md
git commit -m "chore(r7): close M1 foundation gate"
```

---

# Phase R7-M2 — Synchronization Controller

### Task 10: Move Group Sync Behind SyncController

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/sync/SyncController.java`
- Create: `app/src/main/java/com/waworkspace/android/sync/GroupGrabberController.java`
- Create: `app/src/test/java/com/waworkspace/android/sync/TerminalConsensusTest.java`
- Modify: `app/src/main/java/com/waworkspace/android/accessibility/ExtractionAccessibilityService.java`

**Interfaces:**
- `SyncController.onObservation(ScreenObservation)` returns desired state/action, not direct UI operations.
- Terminal consensus requires repeated proof; default confirmation count = 3.

- [ ] Write tests proving a single no-op scroll cannot produce `COMPLETE`.
- [ ] Port primary Groups-filter path from R6 without changing row semantics.
- [ ] Port Watermelon/R6 grabber as explicit fallback controller.
- [ ] Preserve discovery provenance `GROUPS_FILTER` vs `CHAT_GRABBER`.
- [ ] Verify controlled reference list discovery rules from Architecture Freeze Section 22.2.
- [ ] Commit as `feat(r7): migrate group synchronization to verified controller`.

---

# Phase R7-M3 — Navigation, Conversation Verification, Smart Scroll

### Task 11: Verified Conversation Navigation

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/navigation/ConversationNavigator.java`
- Create: `app/src/main/java/com/waworkspace/android/extraction/ConversationVerifier.java`
- Create: `app/src/test/java/com/waworkspace/android/extraction/ConversationVerifierTest.java`

- [ ] Require exact normalized title before extraction.
- [ ] Treat title mismatch as hard rejection.
- [ ] Treat weak identity-hint mismatch as bounded re-open/recovery.
- [ ] Prove no link persistence path is reachable before verifier PASS.
- [ ] Commit.

### Task 12: Semantic Smart Scroll

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/navigation/SmartScrollController.java`
- Create: `app/src/test/java/com/waworkspace/android/navigation/SmartScrollControllerTest.java`

- [ ] Implement semantic directions `OLDER`, `NEWER`, `LIST_FORWARD`, `LIST_BACKWARD`.
- [ ] Strategy order: verified node action -> verified alternate container -> safe-bounds gesture -> recovery.
- [ ] Require viewport/telemetry postcondition to accept scroll success.
- [ ] Require terminal consensus for end/start boundaries.
- [ ] Commit.

---

# Phase R7-M4 — Durable Queue and Deep Extraction

### Task 13: Repository Boundary and Immutable Operation Queue

**Files:**
- Modify: `app/src/main/java/com/waworkspace/android/persistence/WorkspaceDb.java`
- Create: `app/src/main/java/com/waworkspace/android/persistence/GroupRepository.java`
- Create: `app/src/main/java/com/waworkspace/android/persistence/LinkRepository.java`
- Create: `app/src/main/java/com/waworkspace/android/persistence/OperationRepository.java`
- Create: `app/src/main/java/com/waworkspace/android/persistence/CheckpointRepository.java`
- Create: `app/src/test/java/com/waworkspace/android/persistence/QueueImmutabilityTest.java`

- [ ] Add migration preserving all R6 data.
- [ ] Copy selected groups into an immutable operation queue at run start.
- [ ] Enforce statuses `PENDING/OPENING/VERIFYING/EXTRACTING/PAUSED/COMPLETED/FAILED`.
- [ ] Prove later Sync changes cannot mutate active queue membership/order.
- [ ] Commit.

### Task 14: Deep Extraction Strategy

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/extraction/ExtractionController.java`
- Create: `app/src/main/java/com/waworkspace/android/extraction/DeepExtractionStrategy.java`
- Create: `app/src/test/java/com/waworkspace/android/extraction/DeepExtractionIdempotencyTest.java`

- [ ] Parse visible HTTP/HTTPS from verified conversation observations.
- [ ] Normalize/deduplicate by normalized URL + conversation key + contextual fingerprint.
- [ ] Persist each batch transactionally before requesting next scroll.
- [ ] Save checkpoint after durable batch commit.
- [ ] Prove replay/restart does not corrupt duplicates or lose completed batch.
- [ ] Commit.

---

# Phase R7-M5 — New-Only and Resume Semantics

### Task 15: Evidence-Based High-Water Checkpoint

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/extraction/NewOnlyExtractionStrategy.java`
- Create: `app/src/test/java/com/waworkspace/android/extraction/NewOnlyCheckpointTest.java`

- [ ] Build durable checkpoint from contextual fingerprint, viewport signature, useful visible timestamp context, and known URL fingerprints.
- [ ] Stop on strong prior-checkpoint match, unread boundary, or bounded historical no-progress evidence.
- [ ] Commit new checkpoint only after current extracted data is durable.
- [ ] Prove resume does not intentionally replay the full history.
- [ ] Commit.

---

# Phase R7-M6 — Recovery, Watchdog, Pause/Resume/Stop

### Task 16: Central Recovery Controller

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/navigation/RecoveryController.java`
- Create: `app/src/test/java/com/waworkspace/android/navigation/RecoveryControllerTest.java`

- [ ] Implement recovery tiers: re-observe -> reclassify -> retry -> back to known screen -> relaunch package -> restore durable item -> bounded fail/continue.
- [ ] Route repeated failure through `CircuitBreaker`.
- [ ] Prove failed item advances to next healthy queue item.
- [ ] Commit.

### Task 17: Watchdog and Lifecycle Reconstruction

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/accessibility/AccessibilityEventBuffer.java`
- Modify: `app/src/main/java/com/waworkspace/android/automation/AutomationEngine.java`
- Create: `app/src/test/java/com/waworkspace/android/automation/LifecycleReconstructionTest.java`

- [ ] Detect event silence, stuck same-state loops, stale window, gesture timeout, service recreation, unexpected package change.
- [ ] Pause at atomic boundary and save checkpoint.
- [ ] Resume from durable state after screen re-verification.
- [ ] Stop increments generation and invalidates stale callbacks.
- [ ] Commit.

---

# Phase R7-M7 — Runtime Profiles, Diagnostic Capture, Replay

### Task 18: Versioned Runtime Profiles

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/profile/RuntimeProfileKey.java`
- Create: `app/src/main/java/com/waworkspace/android/profile/RuntimeProfile.java`
- Create: `app/src/main/java/com/waworkspace/android/profile/RuntimeProfileRepository.java`
- Create: `app/src/test/java/com/waworkspace/android/profile/RuntimeProfileVersioningTest.java`

- [ ] Key by package + app version + locale + Android version + device class + observable profile/instance.
- [ ] Downgrade applicability after WhatsApp version change.
- [ ] Learn conservatively from repeated verified success only.
- [ ] Never allow profile confidence to bypass verifier.
- [ ] Commit.

### Task 19: Sanitized Diagnostic Capture and JVM Replay

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/diagnostics/DiagnosticCapture.java`
- Create: `app/src/main/java/com/waworkspace/android/diagnostics/ReplayFixture.java`
- Create: `app/src/test/java/com/waworkspace/android/diagnostics/ReplayFixtureTest.java`

- [ ] Capture structural node data, classifier evidence, row decisions, telemetry, transitions, actions, verification, profile decisions.
- [ ] Hash/redact text by default.
- [ ] Replay fixtures through classifiers, adapters, group parsing, scroll selection, and terminal consensus.
- [ ] Commit.

---

# Phase R7-M8 — Join Flow Migration

### Task 20: Verified Join Engine

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/join/JoinController.java`
- Create: `app/src/main/java/com/waworkspace/android/join/InviteClassifier.java`
- Create: `app/src/main/java/com/waworkspace/android/join/JoinTargetResolver.java`
- Create: `app/src/main/java/com/waworkspace/android/join/JoinResultVerifier.java`
- Create: `app/src/test/java/com/waworkspace/android/join/JoinTargetIdentityTest.java`

- [ ] Implement `OPEN_INVITE -> CLASSIFY_INVITE -> RESOLVE_ACTION_TARGET -> SNAPSHOT_TARGET -> REACQUIRE_AND_VERIFY_SAME_TARGET -> DISPATCH_CLICK -> OBSERVE -> VERIFY_JOIN_RESULT`.
- [ ] Preserve candidate relationship during parent fallback.
- [ ] Do not accept `performAction(true)` as join success.
- [ ] Verify actual UI result such as request-sent/cancel-request/member state evidence before commit.
- [ ] Quarantine a failed invite after bounded retries and continue.
- [ ] Commit.

---

# Phase R7-M9 — Thin Accessibility Host and Bridge Facade

### Task 21: AutomationFacade and Service Decomposition

**Files:**
- Create: `app/src/main/java/com/waworkspace/android/automation/AutomationFacade.java`
- Modify: `app/src/main/java/com/waworkspace/android/accessibility/ExtractionAccessibilityService.java`
- Modify: native WebView bridge implementation
- Test: `app/src/test/java/com/waworkspace/android/compat/BridgeParityTest.java`

- [ ] Route existing bridge commands through `AutomationFacade` without renaming external commands.
- [ ] Restrict `ExtractionAccessibilityService` to Android host responsibilities.
- [ ] Remove migrated high-level Sync/Extraction/Recovery/Join decisions from service.
- [ ] Run parity tests against all unchanged commands.
- [ ] Commit.

---

# Phase R7-M10 — Build, Regression, and Field Acceptance

### Task 22: Authoritative CI Gate

**Files:**
- Modify: `.github/workflows/android-ci.yml`
- Create: `docs/R7_BUILD_VERIFICATION.md`

- [ ] Run JDK 17.
- [ ] Run `testDebugUnitTest`.
- [ ] Run `lintDebug`.
- [ ] Run `assembleDebug`.
- [ ] Run `assembleRelease`.
- [ ] Run DB migration and bridge compatibility tests.
- [ ] Upload APK and diagnostic test reports.
- [ ] Reject unintended INTERNET permission/private DB access.
- [ ] Record exact commit SHA and artifact SHA-256.

### Task 23: Real-Device Field Suite

**Files:**
- Create: `docs/R7_FIELD_ACCEPTANCE.md`
- Create: `docs/R7_FIELD_RESULTS_TEMPLATE.md`

- [ ] Consumer WhatsApp sync.
- [ ] WhatsApp Business sync.
- [ ] Long list terminal consensus.
- [ ] Duplicate group titles.
- [ ] Deep extraction over long history.
- [ ] New-only from known checkpoint.
- [ ] Pause/resume mid-group.
- [ ] Kill/recreate service mid-run.
- [ ] Deliberately failing group followed by healthy group.
- [ ] 20 injected recoverable navigation drifts; require >=95% verified recovery.
- [ ] Stale delayed callback after stop/restart; require zero mutation.
- [ ] WhatsApp version/profile change revalidation.
- [ ] Join-request flow after extraction-core gates pass.
- [ ] Six-hour stability run with zero R7-attributable crash/ANR and memory plateau criteria from the Architecture Freeze.

### Task 24: Release Decision

**Files:**
- Create: `docs/R7_RELEASE_DECISION.md`

- [ ] Label `Production Candidate` only when architecture Sections 22.1-22.5 all pass.
- [ ] Label `Production Ready` only after repeated representative device runs show the same acceptance results with no unresolved critical/high defects.
- [ ] Any unmet field gate remains explicit in the release decision; no inferred PASS.
- [ ] Commit final evidence package.

---

## Required Review Checkpoints

1. **After R7-M1:** Foundation contracts exist and R6 behavior/UI remains unchanged.
2. **After R7-M2:** Sync parity plus terminal-consensus proof.
3. **After R7-M3:** Wrong-chat and false-scroll-success protections proven.
4. **After R7-M4/M5:** Durable Deep/New-only extraction and restart idempotency proven.
5. **After R7-M6/M7:** Bounded recovery, lifecycle reconstruction, profiles, replay diagnostics proven.
6. **After R7-M8:** Join click is postcondition-verified, not dispatch-assumed.
7. **After R7-M9:** Accessibility service is a thin host and bridge remains compatible.
8. **After R7-M10:** CI + real-device gates decide the release label.

## Implementation Order Rule

Do not start a later migration phase to work around a failing earlier phase. Fix or explicitly revert the failing phase until the previous verified baseline is green. The project must remain installable/testable at each merged milestone.
