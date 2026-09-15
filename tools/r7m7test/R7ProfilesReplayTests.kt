import com.alothmany.wa.r7.adapter.BusinessWhatsAppAdapter
import com.alothmany.wa.r7.observe.ScreenType
import com.alothmany.wa.r7.profile.*
import com.alothmany.wa.r7.diagnostics.*
import com.alothmany.wa.r7.snapshot.NodeSnapshot
import com.alothmany.wa.r7.sync.*
import com.alothmany.wa.r7.navigation.*

private fun checkThat(condition: Boolean, message: String) {
    if (!condition) error(message)
}

private fun node(
    viewId: String,
    pkg: String = "com.whatsapp.w4b",
    selected: Boolean = false,
    scrollable: Boolean = false,
    textHash: String = "deadbeef"
) = NodeSnapshot(
    viewId = viewId,
    className = if (scrollable) "androidx.recyclerview.widget.RecyclerView" else "android.view.View",
    textHash = textHash,
    contentDescriptionHash = "",
    left = 0, top = 0, right = 100, bottom = 100,
    clickable = true,
    scrollable = scrollable,
    selected = selected,
    enabled = true,
    visibleToUser = true,
    depth = 1,
    parentSignature = "parent",
    childCount = 0,
    packageName = pkg,
    windowId = 7,
    capturedAt = 1000L
)

fun main() {
    val oldKey = RuntimeProfileKey(
        packageName = "com.whatsapp.w4b",
        appVersion = "2.26.10",
        localeTag = "ar-YE",
        androidVersion = "13",
        deviceClass = "SM-S908U",
        instanceIdentity = "personal"
    )
    val newKey = oldKey.copy(appVersion = "2.26.11")
    checkThat(oldKey.stableId() != newKey.stableId(), "version must participate in profile identity")
    checkThat(RuntimeProfileApplicability.score(oldKey, oldKey) == 1.0, "exact profile must score 1.0")
    val upgradedScore = RuntimeProfileApplicability.score(oldKey, newKey)
    checkThat(upgradedScore in 0.1..0.5, "version change must sharply downgrade applicability")

    val repo = RuntimeProfileRepository(InMemoryRuntimeProfileStore(), verifiedSuccessThreshold = 3)
    repeat(2) {
        repo.recordSuccess(oldKey, ProfileHintType.GROUP_FILTER_VIEW_ID, "com.whatsapp.w4b:id/groups", verified = true, now = 1000L + it)
    }
    checkThat(repo.recommendations(oldKey, ProfileHintType.GROUP_FILTER_VIEW_ID).isEmpty(), "two successes must not activate a learned hint")
    repo.recordSuccess(oldKey, ProfileHintType.GROUP_FILTER_VIEW_ID, "com.whatsapp.w4b:id/groups", verified = false, now = 1003L)
    checkThat(repo.recommendations(oldKey, ProfileHintType.GROUP_FILTER_VIEW_ID).isEmpty(), "unverified success must never train profile")
    repo.recordSuccess(oldKey, ProfileHintType.GROUP_FILTER_VIEW_ID, "com.whatsapp.w4b:id/groups", verified = true, now = 1004L)
    val active = repo.recommendations(oldKey, ProfileHintType.GROUP_FILTER_VIEW_ID)
    checkThat(active.size == 1, "third verified success should activate candidate")
    checkThat(active.single().requiresVerification, "profile recommendation must never bypass verifier")
    val confidenceBefore = active.single().confidence
    repo.recordFailure(oldKey, ProfileHintType.GROUP_FILTER_VIEW_ID, "com.whatsapp.w4b:id/groups", now = 1005L)
    val confidenceAfter = repo.recommendations(oldKey, ProfileHintType.GROUP_FILTER_VIEW_ID).single().confidence
    checkThat(confidenceAfter < confidenceBefore, "verified failure must lower confidence")

    val storedProfile = RuntimeProfile(
        key = oldKey,
        hints = listOf(RuntimeProfileHint(ProfileHintType.SCROLL_STRATEGY, "PRIMARY_NODE", 4, 1, 0.57, 2000L)),
        lastSeenAt = 2000L
    )
    val profileEncoded = RuntimeProfileCodec.encode(storedProfile)
    checkThat(RuntimeProfileCodec.decode(profileEncoded) == storedProfile, "runtime profile codec must round-trip exactly")

    val nodes = listOf(
        node("com.whatsapp.w4b:id/groups", selected = true),
        node("com.whatsapp.w4b:id/conversations", scrollable = true)
    )
    val capture = DiagnosticCapture(maxNodes = 64)
    val bundle = capture.capture(
        runId = "run-7",
        reason = "sync-terminal-failure",
        nodes = nodes,
        classifierEvidence = listOf("groups-filter-selected", "2-valid-chat-rows"),
        rowDecisions = listOf("row#1:accepted", "row#2:rejected:noise"),
        scrollTelemetry = "count=4,last=1000",
        transitions = listOf("VERIFY_GROUPS->CAPTURE_VISIBLE_GROUPS"),
        actions = listOf("SCROLL_FORWARD:dispatched"),
        verification = listOf("viewport-progress:false"),
        profileDecisions = listOf("GROUP_FILTER_VIEW_ID:preferred")
    )
    checkThat(bundle.nodes.all { it.textHash == "deadbeef" }, "diagnostic capture must preserve only pre-hashed text evidence")
    checkThat(bundle.toCanonicalText().contains("deadbeef"), "hashed evidence should remain replayable")
    checkThat(!bundle.toCanonicalText().contains("secret message"), "raw message content must not appear in diagnostic output")

    val fixture = ReplayFixture(
        capturedAt = 1000L,
        nodes = nodes,
        groupProbe = GroupProbeEvidence(
            title = "Study Group",
            visibleLabels = listOf("Group info", "Exit group"),
            packageName = "com.whatsapp.w4b"
        ),
        scrollDirection = SemanticScrollDirection.LIST_FORWARD,
        scrollAttempts = listOf(
            ReplayScrollAttempt(ScrollStrategy.PRIMARY_NODE, true, "v1", "v1", false),
            ReplayScrollAttempt(ScrollStrategy.ALTERNATE_NODE, true, "v1", "v1", false),
            ReplayScrollAttempt(ScrollStrategy.SAFE_GESTURE, true, "v1", "v1", false)
        ),
        terminalEvidence = listOf(
            TerminalEvidence("v1", "a", "z", emptySet(), true, false, false),
            TerminalEvidence("v1", "a", "z", emptySet(), true, false, false),
            TerminalEvidence("v1", "a", "z", emptySet(), true, false, false)
        )
    )
    val encoded = fixture.encode()
    val decoded = ReplayFixture.decode(encoded)
    checkThat(decoded == fixture, "replay fixture encoding must be deterministic and lossless")

    val replay = ReplayEngine().replay(decoded)
    checkThat(replay.screen.type == ScreenType.GROUPS_LIST, "classifier replay should identify groups list")
    checkThat(replay.adapterName == BusinessWhatsAppAdapter::class.simpleName, "business adapter should be selected deterministically")
    checkThat(replay.groupsFilterFound, "adapter replay should find selected groups filter")
    checkThat(replay.groupDecision?.classification == GrabberClassification.GROUP, "group probe replay should prove explicit group")
    checkThat(replay.finalScrollDirective?.action == ScrollDirectiveAction.CONFIRM_BOUNDARY, "one semantic scroll cycle should remain boundary-confirming")
    checkThat(replay.terminalDecision?.status == TerminalStatus.TERMINAL, "terminal consensus replay should be deterministic")

    println("R7 M7 PROFILE/REPLAY TESTS PASS")
}
