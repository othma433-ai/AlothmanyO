import com.alothmany.wa.r7.extraction.ConversationEvidence
import com.alothmany.wa.r7.extraction.ConversationVerificationStatus
import com.alothmany.wa.r7.extraction.ConversationVerifier
import com.alothmany.wa.r7.navigation.ConversationNavigator
import com.alothmany.wa.r7.navigation.NavigationAction
import com.alothmany.wa.r7.navigation.ScrollDirectiveAction
import com.alothmany.wa.r7.navigation.ScrollStrategy
import com.alothmany.wa.r7.navigation.SemanticScrollDirection
import com.alothmany.wa.r7.navigation.ScrollAttemptEvidence
import com.alothmany.wa.r7.navigation.SmartScrollController

private fun checkTrue(value: Boolean, message: String) { if (!value) error(message) }
private fun checkEq(expected: Any?, actual: Any?, message: String) { if (expected != actual) error("$message expected=$expected actual=$actual") }

fun testConversationVerifierRequiresExactNormalizedTitle() {
    val verifier = ConversationVerifier()
    val pass = verifier.verify(
        ConversationEvidence(
            expectedPackage = "com.whatsapp",
            observedPackage = "com.whatsapp",
            expectedTitle = "  Study   Group  ",
            observedTitle = "study group"
        )
    )
    checkEq(ConversationVerificationStatus.PASS, pass.status, "normalized exact title must pass")
    checkTrue(pass.verifiedConversation != null, "PASS must mint verified token")

    val containsOnly = verifier.verify(
        ConversationEvidence(
            expectedPackage = "com.whatsapp",
            observedPackage = "com.whatsapp",
            expectedTitle = "Study Group",
            observedTitle = "Study Group Backup"
        )
    )
    checkEq(ConversationVerificationStatus.HARD_REJECT, containsOnly.status, "contains-only title match must be rejected")
    checkEq(null, containsOnly.verifiedConversation, "hard rejection must never mint token")
}

fun testConversationVerifierRejectsPackageMismatchAndTreatsIdentityAsWeakEvidence() {
    val verifier = ConversationVerifier()
    val wrongPackage = verifier.verify(
        ConversationEvidence("com.whatsapp", "com.whatsapp.w4b", "Team", "Team")
    )
    checkEq(ConversationVerificationStatus.HARD_REJECT, wrongPackage.status, "package mismatch is hard rejection")

    val weak = verifier.verify(
        ConversationEvidence(
            expectedPackage = "com.whatsapp",
            observedPackage = "com.whatsapp",
            expectedTitle = "Team",
            observedTitle = "Team",
            expectedIdentitySignature = "expected-signature",
            observedIdentitySignature = "different-signature"
        )
    )
    checkEq(ConversationVerificationStatus.WEAK_MISMATCH, weak.status, "identity mismatch is bounded recovery evidence")
    checkEq(null, weak.verifiedConversation, "weak mismatch cannot admit extraction")
}

fun testNavigatorBoundsWeakMismatchRecovery() {
    val navigator = ConversationNavigator(maxWeakReopens = 2)
    val weak = ConversationVerifier().verify(
        ConversationEvidence("com.whatsapp", "com.whatsapp", "Team", "Team", "a", "b")
    )
    checkEq(NavigationAction.REOPEN, navigator.onVerification(weak).action, "weak mismatch reopen 1")
    checkEq(NavigationAction.REOPEN, navigator.onVerification(weak).action, "weak mismatch reopen 2")
    checkEq(NavigationAction.RECOVER, navigator.onVerification(weak).action, "after bounded reopens perform one recovery")
    checkEq(NavigationAction.REJECT, navigator.onVerification(weak).action, "weak mismatch after recovery must stop")

    navigator.reset()
    val hard = ConversationVerifier().verify(
        ConversationEvidence("com.whatsapp", "com.whatsapp", "Team", "Wrong Team")
    )
    checkEq(NavigationAction.REJECT, navigator.onVerification(hard).action, "hard title mismatch cannot be retried as identity drift")
}

fun testSmartScrollUsesStrictFallbackOrder() {
    val controller = SmartScrollController(requiredTerminalConfirmations = 3)
    val begin = controller.begin(SemanticScrollDirection.OLDER)
    checkEq(ScrollDirectiveAction.TRY_PRIMARY_NODE, begin.action, "semantic scroll starts with verified primary node")

    val primary = controller.observeAttempt(
        SemanticScrollDirection.OLDER,
        ScrollStrategy.PRIMARY_NODE,
        ScrollAttemptEvidence(true, "vp", "vp", false)
    )
    checkEq(ScrollDirectiveAction.TRY_ALTERNATE_NODE, primary.action, "primary no-progress falls back to alternate")

    val alternate = controller.observeAttempt(
        SemanticScrollDirection.OLDER,
        ScrollStrategy.ALTERNATE_NODE,
        ScrollAttemptEvidence(false, "vp", "vp", false)
    )
    checkEq(ScrollDirectiveAction.TRY_SAFE_GESTURE, alternate.action, "alternate no-progress falls back to gesture")

    val gesture = controller.observeAttempt(
        SemanticScrollDirection.OLDER,
        ScrollStrategy.SAFE_GESTURE,
        ScrollAttemptEvidence(true, "vp", "vp", false)
    )
    checkEq(ScrollDirectiveAction.CONFIRM_BOUNDARY, gesture.action, "one full no-progress cycle only starts boundary confirmation")
    checkEq(1, gesture.terminalConfirmations, "first boundary confirmation")
}

fun testSmartScrollAcceptsViewportOrScrollTelemetryProgress() {
    val controller = SmartScrollController(3)
    controller.begin(SemanticScrollDirection.LIST_FORWARD)
    val viewportProgress = controller.observeAttempt(
        SemanticScrollDirection.LIST_FORWARD,
        ScrollStrategy.PRIMARY_NODE,
        ScrollAttemptEvidence(true, "vp1", "vp2", false)
    )
    checkEq(ScrollDirectiveAction.PROGRESSED, viewportProgress.action, "viewport change verifies progress")

    controller.begin(SemanticScrollDirection.NEWER)
    val telemetryProgress = controller.observeAttempt(
        SemanticScrollDirection.NEWER,
        ScrollStrategy.PRIMARY_NODE,
        ScrollAttemptEvidence(true, "same", "same", true)
    )
    checkEq(ScrollDirectiveAction.PROGRESSED, telemetryProgress.action, "scroll telemetry verifies progress when viewport signature is stable")
    checkEq(0, telemetryProgress.terminalConfirmations, "real progress resets boundary proof")
}

fun testSmartScrollRequiresThreeCompleteNoProgressCyclesForBoundary() {
    val controller = SmartScrollController(requiredTerminalConfirmations = 3)
    repeat(2) { cycle ->
        controller.begin(SemanticScrollDirection.OLDER)
        controller.observeAttempt(SemanticScrollDirection.OLDER, ScrollStrategy.PRIMARY_NODE, ScrollAttemptEvidence(true, "end", "end", false))
        controller.observeAttempt(SemanticScrollDirection.OLDER, ScrollStrategy.ALTERNATE_NODE, ScrollAttemptEvidence(true, "end", "end", false))
        val result = controller.observeAttempt(SemanticScrollDirection.OLDER, ScrollStrategy.SAFE_GESTURE, ScrollAttemptEvidence(true, "end", "end", false))
        checkEq(ScrollDirectiveAction.CONFIRM_BOUNDARY, result.action, "cycle ${cycle + 1} must not terminate")
    }
    controller.begin(SemanticScrollDirection.OLDER)
    controller.observeAttempt(SemanticScrollDirection.OLDER, ScrollStrategy.PRIMARY_NODE, ScrollAttemptEvidence(true, "end", "end", false))
    controller.observeAttempt(SemanticScrollDirection.OLDER, ScrollStrategy.ALTERNATE_NODE, ScrollAttemptEvidence(true, "end", "end", false))
    val third = controller.observeAttempt(SemanticScrollDirection.OLDER, ScrollStrategy.SAFE_GESTURE, ScrollAttemptEvidence(true, "end", "end", false))
    checkEq(ScrollDirectiveAction.BOUNDARY, third.action, "third full stable no-progress cycle proves boundary")
    checkEq(3, third.terminalConfirmations, "boundary proof count")
}

fun testGestureDispatchFailureRequiresRecoveryNotFalseBoundary() {
    val controller = SmartScrollController(3)
    controller.begin(SemanticScrollDirection.OLDER)
    controller.observeAttempt(SemanticScrollDirection.OLDER, ScrollStrategy.PRIMARY_NODE, ScrollAttemptEvidence(false, "vp", "vp", false))
    controller.observeAttempt(SemanticScrollDirection.OLDER, ScrollStrategy.ALTERNATE_NODE, ScrollAttemptEvidence(false, "vp", "vp", false))
    val result = controller.observeAttempt(
        SemanticScrollDirection.OLDER,
        ScrollStrategy.SAFE_GESTURE,
        ScrollAttemptEvidence(false, "vp", "vp", false)
    )
    checkEq(ScrollDirectiveAction.RECOVER, result.action, "undispatched gesture is recovery evidence, not terminal evidence")
    checkEq(0, result.terminalConfirmations, "failed dispatch cannot accumulate terminal proof")
}

fun main() {
    testConversationVerifierRequiresExactNormalizedTitle()
    testConversationVerifierRejectsPackageMismatchAndTreatsIdentityAsWeakEvidence()
    testNavigatorBoundsWeakMismatchRecovery()
    testSmartScrollUsesStrictFallbackOrder()
    testSmartScrollAcceptsViewportOrScrollTelemetryProgress()
    testSmartScrollRequiresThreeCompleteNoProgressCyclesForBoundary()
    testGestureDispatchFailureRequiresRecoveryNotFalseBoundary()
    println("R7 M3 NAVIGATION TESTS PASS")
}
