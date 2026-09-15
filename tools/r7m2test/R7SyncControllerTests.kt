import com.alothmany.wa.r7.sync.DiscoveryProvenance
import com.alothmany.wa.r7.sync.GroupGrabberController
import com.alothmany.wa.r7.sync.GroupProbeEvidence
import com.alothmany.wa.r7.sync.GrabberClassification
import com.alothmany.wa.r7.sync.SyncAction
import com.alothmany.wa.r7.sync.SyncController
import com.alothmany.wa.r7.sync.TerminalConsensus
import com.alothmany.wa.r7.sync.TerminalEvidence
import com.alothmany.wa.r7.sync.TerminalStatus

private fun checkTrue(value: Boolean, message: String) { if (!value) error(message) }
private fun checkEq(expected: Any?, actual: Any?, message: String) { if (expected != actual) error("$message expected=$expected actual=$actual") }

fun testSingleNoOpScrollCannotComplete() {
    val consensus = TerminalConsensus(requiredConfirmations = 3)
    val first = consensus.observe(
        TerminalEvidence(
            viewportSignature = "vp-end",
            firstStableKey = "a",
            lastStableKey = "z",
            newStableKeys = emptySet(),
            scrollAccepted = false,
            scrollProgressed = false,
            telemetryAdvanced = false
        )
    )
    checkEq(TerminalStatus.CONFIRMING, first.status, "one no-op must only start confirmation")
    checkEq(1, first.confirmations, "one no-op confirmation count")
}

fun testTerminalRequiresThreeMatchingNoProgressObservations() {
    val consensus = TerminalConsensus(requiredConfirmations = 3)
    val evidence = TerminalEvidence("vp-end", "a", "z", emptySet(), false, false, false)
    checkEq(TerminalStatus.CONFIRMING, consensus.observe(evidence).status, "terminal evidence 1")
    checkEq(TerminalStatus.CONFIRMING, consensus.observe(evidence).status, "terminal evidence 2")
    val third = consensus.observe(evidence)
    checkEq(TerminalStatus.TERMINAL, third.status, "terminal evidence 3")
    checkEq(3, third.confirmations, "terminal confirmation count")
}

fun testProgressAndNewGroupResetConsensus() {
    val consensus = TerminalConsensus(requiredConfirmations = 3)
    val end = TerminalEvidence("vp-end", "a", "z", emptySet(), true, false, false)
    consensus.observe(end)
    consensus.observe(end)
    val progressed = consensus.observe(end.copy(viewportSignature = "vp-next", scrollAccepted = true, scrollProgressed = true, telemetryAdvanced = true))
    checkEq(TerminalStatus.PROGRESS, progressed.status, "real progress must reset terminal proof")
    checkEq(0, progressed.confirmations, "progress resets count")

    val afterNew = consensus.observe(end.copy(newStableKeys = setOf("new-group")))
    checkEq(TerminalStatus.PROGRESS, afterNew.status, "new group must reset terminal proof")
    checkEq(0, afterNew.confirmations, "new group resets count")
}

fun testChangingViewportCannotAccumulateTerminalEvidence() {
    val consensus = TerminalConsensus(requiredConfirmations = 3)
    val one = consensus.observe(TerminalEvidence("vp-1", "a", "z", emptySet(), false, false, false))
    val two = consensus.observe(TerminalEvidence("vp-2", "a", "z", emptySet(), false, false, false))
    val three = consensus.observe(TerminalEvidence("vp-3", "a", "z", emptySet(), false, false, false))
    checkEq(1, one.confirmations, "first evidence")
    checkEq(TerminalStatus.PROGRESS, two.status, "changed viewport is progress evidence")
    checkEq(TerminalStatus.PROGRESS, three.status, "changed viewport still cannot terminal")
}

fun testSyncControllerDeduplicatesAndPreservesProvenance() {
    val controller = SyncController(requiredTerminalConfirmations = 3)
    val capture1 = controller.captureRows(listOf("Group Alpha", "Group Beta", " Group Alpha "), DiscoveryProvenance.GROUPS_FILTER, "vp1")
    checkEq(2, capture1.newGroups.size, "first capture unique groups")
    checkTrue(capture1.newGroups.all { it.provenance == DiscoveryProvenance.GROUPS_FILTER }, "filter provenance")
    checkEq(SyncAction.SCROLL_FORWARD, capture1.nextAction, "capture continues to scroll")

    val capture2 = controller.captureRows(listOf("group alpha", "Group Beta", "Group Gamma"), DiscoveryProvenance.GROUPS_FILTER, "vp2")
    checkEq(1, capture2.newGroups.size, "case-insensitive stable identity prevents duplicate")
    checkEq("Group Gamma", capture2.newGroups.single().displayName, "new group preserved")
    checkEq(3, controller.totalSeen(), "total stable groups")
}

fun testSyncControllerCompletesOnlyAfterConsensus() {
    val controller = SyncController(requiredTerminalConfirmations = 3)
    controller.captureRows(listOf("A", "B"), DiscoveryProvenance.GROUPS_FILTER, "vp-end")
    // First scroll after discovering rows cannot be terminal because rows were new.
    checkEq(SyncAction.SCROLL_FORWARD, controller.onScroll(false, false, false, "vp-end").action, "new groups prevent terminal")
    controller.captureRows(listOf("A", "B"), DiscoveryProvenance.GROUPS_FILTER, "vp-end")
    checkEq(SyncAction.SCROLL_FORWARD, controller.onScroll(false, false, false, "vp-end").action, "terminal confirmation 1")
    controller.captureRows(listOf("A", "B"), DiscoveryProvenance.GROUPS_FILTER, "vp-end")
    checkEq(SyncAction.SCROLL_FORWARD, controller.onScroll(false, false, false, "vp-end").action, "terminal confirmation 2")
    controller.captureRows(listOf("A", "B"), DiscoveryProvenance.GROUPS_FILTER, "vp-end")
    val terminal = controller.onScroll(false, false, false, "vp-end")
    checkEq(SyncAction.COMPLETE, terminal.action, "third clean confirmation completes")
    checkEq(3, terminal.terminalConfirmations, "controller exposes terminal proof count")
}

fun testGrabberIsConservativeAndTagsProvenance() {
    val grabber = GroupGrabberController()
    val privateChat = grabber.classify(GroupProbeEvidence("Alice", listOf("online", "message")))
    checkEq(GrabberClassification.NOT_PROVEN, privateChat.classification, "private chat must not be guessed as group")

    val explicitGroup = grabber.classify(GroupProbeEvidence("Study Group", listOf("Group info", "Add members", "Media, links, and docs")))
    checkEq(GrabberClassification.GROUP, explicitGroup.classification, "explicit group evidence")
    checkEq(DiscoveryProvenance.CHAT_GRABBER, explicitGroup.candidate?.provenance, "grabber provenance")
    checkTrue(explicitGroup.identityHint.isNotBlank(), "strong group evidence creates identity hint")

    val noise = grabber.classify(GroupProbeEvidence("Archived", listOf("Archived", "Search", "Settings")))
    checkEq(GrabberClassification.REJECTED, noise.classification, "navigation noise must be rejected")
}

fun main() {
    testSingleNoOpScrollCannotComplete()
    testTerminalRequiresThreeMatchingNoProgressObservations()
    testProgressAndNewGroupResetConsensus()
    testChangingViewportCannotAccumulateTerminalEvidence()
    testSyncControllerDeduplicatesAndPreservesProvenance()
    testSyncControllerCompletesOnlyAfterConsensus()
    testGrabberIsConservativeAndTagsProvenance()
    println("R7 M2 SYNC TESTS PASS")
}
