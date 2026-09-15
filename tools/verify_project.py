from pathlib import Path
import sys, xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
errors=[]
required=[
    'settings.gradle.kts','build.gradle.kts','app/build.gradle.kts','app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/alothmany/wa/accessibility/WaAccessibilityService.kt',
    'app/src/main/java/com/alothmany/wa/accessibility/WhatsAppUiBridge.kt',
    'app/src/main/java/com/alothmany/wa/automation/AutomationEngine.kt',
    'app/src/main/java/com/alothmany/wa/automation/ActionAutomationEngine.kt',
    'app/src/main/java/com/alothmany/wa/data/AppDatabase.kt',
    'app/src/main/java/com/alothmany/wa/data/ActionRepository.kt',
    'app/src/main/java/com/alothmany/wa/domain/SendVerificationPolicy.kt',
    'app/src/main/java/com/alothmany/wa/export/ActionReportExporter.kt',
    'app/src/main/java/com/alothmany/wa/service/AutomationForegroundService.kt',
    'app/src/main/java/com/alothmany/wa/ui/MainActivity.kt',
    '.github/workflows/android-debug.yml'
]
for f in required:
    if not (root/f).exists(): errors.append(f'missing {f}')
for xml in (root/'app/src/main/res').rglob('*.xml'):
    try: ET.parse(xml)
    except Exception as e: errors.append(f'bad xml {xml.relative_to(root)}: {e}')
try: ET.parse(root/'app/src/main/AndroidManifest.xml')
except Exception as e: errors.append(f'bad manifest: {e}')

build=(root/'app/build.gradle.kts').read_text()
if 'versionName = "2.2.0"' not in build: errors.append('wrong version')
if 'versionCode = 20200' not in build: errors.append('wrong versionCode')
if 'androidx.room:room-runtime:2.8.5' not in build: errors.append('Room dependency missing')
repo=(root/'app/src/main/java/com/alothmany/wa/data/ExtractionRepository.kt').read_text()
if 'MAX_BATCH = 128' not in repo: errors.append('bounded 128 batch missing')
engine=(root/'app/src/main/java/com/alothmany/wa/automation/AutomationEngine.kt').read_text()
for token in ['PARTIAL','scrolls < 5000','CheckpointRepository','scrollListForwardVerified','lastProgressAt','SyncController','syncViaGroupsFilter','syncViaChatGrabber','ensureImmutableQueue','persistVerifiedViewport','refreshJobCounters']:
    if token not in engine: errors.append(f'extraction invariant missing: {token}')
action=(root/'app/src/main/java/com/alothmany/wa/automation/ActionAutomationEngine.kt').read_text()
for token in ['runInviteScan','runJoin','runPublish','SEND_FENCE_ARMED','VERIFY_PENDING','UNVERIFIED_NO_RESEND','finalizeJob']:
    if token not in action: errors.append(f'action engine invariant missing: {token}')
bridge=(root/'app/src/main/java/com/alothmany/wa/accessibility/WhatsAppUiBridge.kt').read_text()
for token in ['clickExactTarget','performInviteAction','awaitInviteState','ACTION_SET_TEXT','dispatchTap','exactMessageCount','SendVerificationPolicy','scrollListForwardVerified']:
    if token not in bridge: errors.append(f'verified UI action invariant missing: {token}')
db=(root/'app/src/main/java/com/alothmany/wa/data/AppDatabase.kt').read_text()
for token in ['version = 4','MIGRATION_1_2','MIGRATION_2_3','MIGRATION_3_4','action_jobs','action_items','extraction_queue_items','verificationAttempts','lastProgressAt','addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)']:
    if token not in db: errors.append(f'database migration invariant missing: {token}')
service=(root/'app/src/main/java/com/alothmany/wa/service/AutomationForegroundService.kt').read_text()
for token in ['START_STICKY','recoverLatest()','PARTIAL_WAKE_LOCK','ACTION_RUN_ACTION_JOB','latestWork()','SCAN_INVITES','runInviteScan']:
    if token not in service: errors.append(f'service invariant missing: {token}')
ui=(root/'app/src/main/java/com/alothmany/wa/ui/MainActivity.kt').read_text()
for token in ['btnScanInvites','SCAN_INVITES','btnExportActions','Diagnostics:\\n']:
    if token not in ui: errors.append(f'UI invariant missing: {token}')
if '"Diagnostics:\n"' in ui.replace('\\n','\n'):
    pass
manifest=(root/'app/src/main/AndroidManifest.xml').read_text()
for token in ['android.permission.BIND_ACCESSIBILITY_SERVICE','foregroundServiceType="specialUse"','com.whatsapp.w4b']:
    if token not in manifest: errors.append(f'manifest invariant missing: {token}')

# R7-M1 foundation contracts (v2.2 baseline adaptation).
r7_required = [
    'app/src/main/java/com/alothmany/wa/r7/snapshot/NodeSnapshot.kt',
    'app/src/main/java/com/alothmany/wa/r7/snapshot/SnapshotSignature.kt',
    'app/src/main/java/com/alothmany/wa/r7/snapshot/ActionTargetEvidence.kt',
    'app/src/main/java/com/alothmany/wa/r7/accessibility/NodeSnapshotFactory.kt',
    'app/src/main/java/com/alothmany/wa/r7/accessibility/AccessibilityGateway.kt',
    'app/src/main/java/com/alothmany/wa/r7/accessibility/LiveNodeResolutionPolicy.kt',
    'app/src/main/java/com/alothmany/wa/r7/observe/ScreenStabilityDetector.kt',
    'app/src/main/java/com/alothmany/wa/r7/adapter/WhatsAppAdapter.kt',
    'app/src/main/java/com/alothmany/wa/r7/automation/ActionContracts.kt',
    'app/src/main/java/com/alothmany/wa/r7/automation/CircuitBreaker.kt',
    'app/src/main/java/com/alothmany/wa/r7/diagnostics/TraceModels.kt',
    'tools/run_r7_m1_tests.sh',
    'tools/r7_m1_contract.sh',
]
for f in r7_required:
    if not (root/f).exists(): errors.append(f'R7-M1 missing {f}')

r7_m2_required = [
    'app/src/main/java/com/alothmany/wa/r7/sync/SyncModels.kt',
    'app/src/main/java/com/alothmany/wa/r7/sync/TerminalConsensus.kt',
    'app/src/main/java/com/alothmany/wa/r7/sync/SyncController.kt',
    'app/src/main/java/com/alothmany/wa/r7/sync/GroupGrabberController.kt',
    'tools/run_r7_m2_tests.sh',
    'tools/r7_m2_contract.sh',
]
for f in r7_m2_required:
    if not (root/f).exists(): errors.append(f'R7-M2 missing {f}')
if 'navigateToGroupsFilterVerified' not in bridge:
    errors.append('R7-M2 verified Groups-filter navigation missing')
for token in ['SYNC_TERMINAL_CONSENSUS','SYNC_STRATEGY_SWITCH','CHAT_GRABBER_TERMINAL_CONSENSUS','classification = candidate.provenance.name']:
    if token not in engine: errors.append(f'R7-M2 sync invariant missing: {token}')
if 'quietRounds' in engine or 'bottomEvidence' in engine:
    errors.append('R7-M2 legacy early-terminal counters still present')


r7_m3_required = [
    'app/src/main/java/com/alothmany/wa/r7/extraction/ConversationVerifier.kt',
    'app/src/main/java/com/alothmany/wa/r7/navigation/ConversationNavigator.kt',
    'app/src/main/java/com/alothmany/wa/r7/navigation/SmartScrollController.kt',
    'tools/run_r7_m3_tests.sh',
    'tools/r7_m3_contract.sh',
]
for f in r7_m3_required:
    if not (root/f).exists(): errors.append(f'R7-M3 missing {f}')
for token in ['openVerifiedConversation','verifiedConversation: VerifiedConversation','SmartScrollController(requiredTerminalConfirmations = 3)','SemanticScrollDirection.OLDER','CHAT_START_TERMINAL_CONSENSUS']:
    if token not in engine: errors.append(f'R7-M3 navigation invariant missing: {token}')
for token in ['semanticScroll','dispatchSemanticNodeScroll','dispatchSemanticGesture','scrollTelemetrySnapshot']:
    if token not in bridge: errors.append(f'R7-M3 smart-scroll bridge invariant missing: {token}')
if 'snapshotMatchesGroup' in engine:
    errors.append('R7-M3 legacy loose conversation matcher still present')
if 'bridge.scrollOlder()' in engine:
    errors.append('R7-M3 legacy extraction scroll path still present')
if 'chatTitle?.contains(name, ignoreCase = true)' in bridge:
    errors.append('R7-M3 contains-only title verification still present')



r7_m4_required = [
    'app/src/main/java/com/alothmany/wa/r7/persistence/QueueModels.kt',
    'app/src/main/java/com/alothmany/wa/r7/persistence/GroupRepository.kt',
    'app/src/main/java/com/alothmany/wa/r7/persistence/LinkRepository.kt',
    'app/src/main/java/com/alothmany/wa/r7/persistence/OperationRepository.kt',
    'app/src/main/java/com/alothmany/wa/r7/persistence/CheckpointRepository.kt',
    'app/src/main/java/com/alothmany/wa/r7/extraction/DeepExtractionStrategy.kt',
    'app/src/main/java/com/alothmany/wa/r7/extraction/ExtractionController.kt',
    'tools/run_r7_m4_tests.sh',
    'tools/test_r7_m4_migration.py',
    'tools/r7_m4_contract.sh',
]
for f in r7_m4_required:
    if not (root/f).exists(): errors.append(f'R7-M4 missing {f}')
for token in ['ensureImmutableQueue','QUEUE_FROZEN','ExtractionQueueStatus.OPENING','ExtractionQueueStatus.EXTRACTING','ExtractionQueueStatus.COMPLETED','persistVerifiedViewport','refreshJobCounters']:
    if token not in engine: errors.append(f'R7-M4 durable extraction invariant missing: {token}')
if 'for (group in groups)' in engine:
    errors.append('R7-M4 extraction still iterates the live synchronized group table')
if 'fallbackToDestructiveMigration' in db:
    errors.append('R7-M4 destructive database migration shortcut present')
dao_text=(root/'app/src/main/java/com/alothmany/wa/data/Daos.kt').read_text()
queue_dao=dao_text.split('interface ExtractionQueueDao', 1)[1] if 'interface ExtractionQueueDao' in dao_text else ''
if 'UPDATE extraction_queue_items SET status = :status' not in queue_dao:
    errors.append('R7-M4 queue mutable-state-only update missing')
for forbidden in ['ordinal = :', 'targetPackage = :', 'expectedTitle = :', 'expectedIdentitySignature = :', 'discoveryProvenance = :']:
    if forbidden in queue_dao: errors.append(f'R7-M4 immutable queue field update present: {forbidden}')


r7_m5_required = [
    'app/src/main/java/com/alothmany/wa/r7/extraction/NewOnlyExtractionStrategy.kt',
    'tools/run_r7_m5_tests.sh',
    'tools/r7_m5_contract.sh',
]
for f in r7_m5_required:
    if not (root/f).exists(): errors.append(f'R7-M5 missing {f}')
for token in ['NewOnlyExtractionStrategy','previousNewOnlyCheckpoint','nextNewOnlyCheckpoint','NEW_ONLY_BOUNDARY_CONFIRMED','NEW_ONLY_HISTORICAL_TERMINAL_CONSENSUS','updateNewOnlyBoundary']:
    if token not in engine: errors.append(f'R7-M5 New-only invariant missing: {token}')
for forbidden in ['anchorOverlap','anchorToken','parseAnchors']:
    if forbidden in engine: errors.append(f'R7-M5 legacy anchor-only logic still present: {forbidden}')
new_only=(root/'app/src/main/java/com/alothmany/wa/r7/extraction/NewOnlyExtractionStrategy.kt').read_text()
for token in ['viewportSignature','timestampContextHashes','urlFingerprints','PRIOR_CHECKPOINT_MATCH','UNREAD_BOUNDARY','HISTORICAL_NO_PROGRESS','legacy = true']:
    if token not in new_only: errors.append(f'R7-M5 checkpoint invariant missing: {token}')



r7_m6_required = [
    'app/src/main/java/com/alothmany/wa/r7/navigation/RecoveryController.kt',
    'app/src/main/java/com/alothmany/wa/r7/automation/Watchdog.kt',
    'app/src/main/java/com/alothmany/wa/r7/automation/RunLifecycle.kt',
    'app/src/main/java/com/alothmany/wa/r7/accessibility/AccessibilityEventBuffer.kt',
    'tools/run_r7_m6_tests.sh',
    'tools/r7_m6_contract.sh',
]
for f in r7_m6_required:
    if not (root/f).exists(): errors.append(f'R7-M6 missing {f}')
for token in ['RecoveryController','checkWatchdog','WATCHDOG_TRIGGERED','PAUSED_AFTER_DURABLE_BATCH','RESUMED_FROM_DURABLE_CHECKPOINT','SMART_SCROLL_RECOVERED']:
    if token not in engine: errors.append(f'R7-M6 recovery invariant missing: {token}')
controller=(root/'app/src/main/java/com/alothmany/wa/automation/AutomationController.kt').read_text()
for token in ['PAUSE_REQUESTED','RunLifecycle','currentGeneration','acceptsCallback']:
    if token not in controller: errors.append(f'R7-M6 lifecycle invariant missing: {token}')
runtime=(root/'app/src/main/java/com/alothmany/wa/accessibility/AutomationRuntime.kt').read_text()
for token in ['eventBuffer','currentServiceGeneration','activeGestureStartedAt']:
    if token not in runtime: errors.append(f'R7-M6 runtime invariant missing: {token}')
if 'dispatchTrackedGesture' not in bridge: errors.append('R7-M6 tracked gesture dispatch missing')
if 'awaitActionBoundary' not in action: errors.append('R7-M6 action atomic pause boundary missing')


r7_m7_required = [
    'app/src/main/java/com/alothmany/wa/r7/profile/RuntimeProfile.kt',
    'app/src/main/java/com/alothmany/wa/r7/profile/RuntimeProfileRepository.kt',
    'app/src/main/java/com/alothmany/wa/r7/profile/AndroidRuntimeProfileStore.kt',
    'app/src/main/java/com/alothmany/wa/r7/diagnostics/DiagnosticCapture.kt',
    'app/src/main/java/com/alothmany/wa/r7/diagnostics/DiagnosticArtifactStore.kt',
    'app/src/main/java/com/alothmany/wa/r7/diagnostics/ReplayFixture.kt',
    'app/src/main/java/com/alothmany/wa/r7/accessibility/R7NodeSnapshotCollector.kt',
    'tools/run_r7_m7_tests.sh',
    'tools/r7_m7_contract.sh',
]
for f in r7_m7_required:
    if not (root/f).exists(): errors.append(f'R7-M7 missing {f}')
profile=(root/'app/src/main/java/com/alothmany/wa/r7/profile/RuntimeProfile.kt').read_text() if (root/'app/src/main/java/com/alothmany/wa/r7/profile/RuntimeProfile.kt').exists() else ''
profile_repo=(root/'app/src/main/java/com/alothmany/wa/r7/profile/RuntimeProfileRepository.kt').read_text() if (root/'app/src/main/java/com/alothmany/wa/r7/profile/RuntimeProfileRepository.kt').exists() else ''
replay=(root/'app/src/main/java/com/alothmany/wa/r7/diagnostics/ReplayFixture.kt').read_text() if (root/'app/src/main/java/com/alothmany/wa/r7/diagnostics/ReplayFixture.kt').exists() else ''
for token in ['packageName','appVersion','localeTag','androidVersion','deviceClass','instanceIdentity','RuntimeProfileCodec']:
    if token not in profile: errors.append(f'R7-M7 profile invariant missing: {token}')
for token in ['verifiedSuccessThreshold: Int = 3','if (!verified || value.isBlank()) return','requiresVerification = true']:
    if token not in profile_repo: errors.append(f'R7-M7 conservative learning invariant missing: {token}')
for token in ['ReplayEngine','ScreenClassifier','GroupGrabberController','SmartScrollController','TerminalConsensus']:
    if token not in replay: errors.append(f'R7-M7 replay invariant missing: {token}')
for token in ['navigateToGroupsFilterVerifiedProfiled','captureR7DiagnosticArtifact','AndroidRuntimeProfileStore']:
    if token not in bridge: errors.append(f'R7-M7 bridge invariant missing: {token}')


r7_m8_required = [
    'app/src/main/java/com/alothmany/wa/r7/join/JoinEngine.kt',
    'tools/run_r7_m8_tests.sh',
    'tools/r7_m8_contract.sh',
]
for f in r7_m8_required:
    if not (root/f).exists(): errors.append(f'R7-M8 missing {f}')
join_engine=(root/'app/src/main/java/com/alothmany/wa/r7/join/JoinEngine.kt').read_text() if (root/'app/src/main/java/com/alothmany/wa/r7/join/JoinEngine.kt').exists() else ''
for token in ['JoinTargetResolver','JoinPostconditionVerifier','JoinTargetIdentityGuard','APPROVAL_REQUEST','COMMUNITY_VIEW']:
    if token not in join_engine: errors.append(f'R7-M8 verified join invariant missing: {token}')
for token in ['prepareInviteAction','dispatchPreparedJoinTarget','clickReacquiredJoinNode','joinPostconditionVerifier.verify']:
    if token not in bridge: errors.append(f'R7-M8 bridge invariant missing: {token}')
for token in ['prepareInviteAction(8_000)','performInviteAction(prepared)','ACTION_TARGET_UNRESOLVED']:
    if token not in action: errors.append(f'R7-M8 action invariant missing: {token}')
if 'performInviteAction()' in action:
    errors.append('R7-M8 legacy join re-search call still present')

service_text=(root/'app/src/main/java/com/alothmany/wa/accessibility/WaAccessibilityService.kt').read_text()
host_text=(root/'app/src/main/java/com/alothmany/wa/accessibility/AndroidAccessibilityServiceHost.kt').read_text() if (root/'app/src/main/java/com/alothmany/wa/accessibility/AndroidAccessibilityServiceHost.kt').exists() else ''
if 'R7ShadowRuntime.onAccessibilityEvent' not in host_text:
    errors.append('R7-M9 host telemetry hook missing')
if 'android.permission.INTERNET' in manifest:
    errors.append('R7-M1 forbids INTERNET permission')


r7_m9_required = [
    'app/src/main/java/com/alothmany/wa/r7/accessibility/AccessibilityHostCoordinator.kt',
    'app/src/main/java/com/alothmany/wa/accessibility/AndroidAccessibilityServiceHost.kt',
    'tools/run_r7_m9_tests.sh',
    'tools/r7_m9_contract.sh',
]
for f in r7_m9_required:
    if not (root/f).exists(): errors.append(f'R7-M9 missing {f}')
if 'private val host = AndroidAccessibilityServiceHost()' not in service_text:
    errors.append('R7-M9 service host delegation missing')
for forbidden in ['AutomationRuntime','R7ShadowRuntime','WhatsAppUiBridge','AutomationEngine','ActionAutomationEngine','eventBuffer','rootInActiveWindow','dispatchGesture']:
    if forbidden in service_text: errors.append(f'R7-M9 service contains runtime/workflow logic: {forbidden}')
if len(service_text.splitlines()) > 40:
    errors.append('R7-M9 accessibility service is not thin')
if 'private val service: AccessibilityService' not in bridge:
    errors.append('R7-M9 bridge still depends on concrete accessibility service')
if 'service.lastEventAt' in bridge:
    errors.append('R7-M9 bridge still reads service-owned event state')
if 'AutomationRuntime.eventBuffer.lastEventAt()' not in bridge:
    errors.append('R7-M9 bridge event telemetry boundary missing')
if 'WaAccessibilityService' in runtime:
    errors.append('R7-M9 runtime still depends on concrete accessibility service')

workflow=(root/'.github/workflows/android-debug.yml').read_text()
if 'WA-Al-Othmany-v2.2.0-R7-M10-debug' not in workflow: errors.append('workflow artifact version mismatch')
if 'R7 M1 foundation tests' not in workflow: errors.append('R7 M1 CI gate missing')
if 'R7 M2 sync tests' not in workflow: errors.append('R7 M2 CI gate missing')
if 'R7 M2 compatibility contract' not in workflow: errors.append('R7 M2 contract CI gate missing')
if 'R7 M3 navigation tests' not in workflow: errors.append('R7 M3 CI gate missing')
if 'R7 M3 compatibility contract' not in workflow: errors.append('R7 M3 contract CI gate missing')
if 'R7 M4 persistence tests' not in workflow: errors.append('R7 M4 CI gate missing')
if 'R7 M4 compatibility contract' not in workflow: errors.append('R7 M4 contract CI gate missing')
if 'R7 M4 migration test' not in workflow: errors.append('R7 M4 migration CI gate missing')
if 'R7 M5 New-only tests' not in workflow: errors.append('R7 M5 CI gate missing')
if 'R7 M5 compatibility contract' not in workflow: errors.append('R7 M5 contract CI gate missing')
if 'R7 M6 recovery tests' not in workflow: errors.append('R7 M6 CI gate missing')
if 'R7 M6 compatibility contract' not in workflow: errors.append('R7 M6 contract CI gate missing')
if 'R7 M7 profile/replay tests' not in workflow: errors.append('R7 M7 CI gate missing')
if 'R7 M7 compatibility contract' not in workflow: errors.append('R7 M7 contract CI gate missing')
if 'R7 M8 verified join tests' not in workflow: errors.append('R7 M8 CI gate missing')
if 'R7 M8 compatibility contract' not in workflow: errors.append('R7 M8 contract CI gate missing')
if 'R7 M9 accessibility host tests' not in workflow: errors.append('R7 M9 CI gate missing')
if 'R7 M9 compatibility contract' not in workflow: errors.append('R7 M9 contract CI gate missing')
if ':app:lintDebug' not in workflow: errors.append('lintDebug CI gate missing')
if ':app:assembleRelease' not in workflow: errors.append('assembleRelease CI gate missing')

# Guard against accidental unbounded UI loops: known while(true) loops must remain inside bounded timeout methods.
bridge_while = bridge.count('while (true)')
bridge_timeouts = bridge.count('withTimeoutOrNull')
if bridge_while > bridge_timeouts + 1:
    errors.append('potential unbounded loops in WhatsAppUiBridge.kt')

# Regression guard for the v2.1 compile blocker: Diagnostics must use escaped newlines, not a raw line break inside a quoted string.
lines=ui.splitlines()
for i,line in enumerate(lines[:-1]):
    if line.strip() == '"Diagnostics:':
        errors.append(f'broken multiline Kotlin string near MainActivity.kt:{i+1}')



# R7-M10 build and certification gates.
r7_m10_required = [
    'tools/r7_m10_contract.sh',
    'tools/verify_r7_m10_artifacts.sh',
    'tools/r7_m10_device_gate.sh',
    'docs/R7_M10_CERTIFICATION.md',
    'docs/R7_M10_FIELD_RESULTS_TEMPLATE.csv',
]
for f in r7_m10_required:
    if not (root/f).exists(): errors.append(f'R7-M10 missing {f}')
workflow=(root/'.github/workflows/android-debug.yml').read_text()
for token in [
    'Android R7-M10 Build Certification',
    "java-version: '17'",
    "gradle-version: '8.11.1'",
    'platforms;android-35',
    'build-tools;35.0.0',
    'R7 M10 build certification contract',
    './tools/verify_r7_m10_artifacts.sh',
    'app-release-unsigned.apk',
    'WA-Al-Othmany-v2.2.0-R7-M10-debug',
    'WA-Al-Othmany-v2.2.0-R7-M10-release-unsigned',
    'WA-Al-Othmany-v2.2.0-R7-M10-build-evidence',
]:
    if token not in workflow: errors.append(f'R7-M10 workflow invariant missing: {token}')
if 'app/build/outputs/apk/release/app-release.apk' in workflow:
    errors.append('R7-M10 workflow still expects an implicitly signed release APK')

if errors:
    print('PROJECT VERIFY FAIL')
    for e in errors: print('-',e)
    sys.exit(1)
print('PROJECT VERIFY PASS')
