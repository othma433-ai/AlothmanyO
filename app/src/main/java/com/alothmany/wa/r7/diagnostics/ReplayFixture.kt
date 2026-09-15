package com.alothmany.wa.r7.diagnostics

import com.alothmany.wa.r7.adapter.BusinessWhatsAppAdapter
import com.alothmany.wa.r7.adapter.ConsumerWhatsAppAdapter
import com.alothmany.wa.r7.adapter.GenericWhatsAppAdapter
import com.alothmany.wa.r7.adapter.WhatsAppAdapter
import com.alothmany.wa.r7.navigation.*
import com.alothmany.wa.r7.observe.ScreenClassifier
import com.alothmany.wa.r7.observe.ScreenObservation
import com.alothmany.wa.r7.snapshot.NodeSnapshot
import com.alothmany.wa.r7.sync.*
import java.util.Base64


data class ReplayScrollAttempt(
    val strategy: ScrollStrategy,
    val dispatched: Boolean,
    val beforeViewportSignature: String,
    val afterViewportSignature: String,
    val telemetryAdvanced: Boolean
)

data class ReplayFixture(
    val capturedAt: Long,
    val nodes: List<NodeSnapshot>,
    val groupProbe: GroupProbeEvidence? = null,
    val scrollDirection: SemanticScrollDirection? = null,
    val scrollAttempts: List<ReplayScrollAttempt> = emptyList(),
    val terminalEvidence: List<TerminalEvidence> = emptyList()
) {
    fun encode(): String = ReplayCodec.encode(this)

    companion object {
        fun decode(value: String): ReplayFixture = ReplayCodec.decode(value)
    }
}

data class ReplayResult(
    val screen: ScreenObservation,
    val adapterName: String,
    val groupsFilterFound: Boolean,
    val groupDecision: GrabberDecision?,
    val finalScrollDirective: SmartScrollDirective?,
    val terminalDecision: TerminalDecision?
)

class ReplayEngine {
    fun replay(fixture: ReplayFixture): ReplayResult {
        val screen = ScreenClassifier().classify(fixture.nodes, fixture.capturedAt)
        val packageName = screen.packageName.ifBlank { fixture.nodes.firstOrNull()?.packageName.orEmpty() }
        val adapter = adapterFor(packageName)
        val groupsFilterFound = adapter.findGroupsFilter(fixture.nodes) != null
        val groupDecision = fixture.groupProbe?.let { GroupGrabberController().classify(it) }

        val scrollDirective = fixture.scrollDirection?.let { direction ->
            val controller = SmartScrollController()
            var directive: SmartScrollDirective = controller.begin(direction)
            fixture.scrollAttempts.forEach { attempt ->
                directive = controller.observeAttempt(
                    direction,
                    attempt.strategy,
                    ScrollAttemptEvidence(
                        dispatched = attempt.dispatched,
                        beforeViewportSignature = attempt.beforeViewportSignature,
                        afterViewportSignature = attempt.afterViewportSignature,
                        telemetryAdvanced = attempt.telemetryAdvanced
                    )
                )
            }
            directive
        }

        val terminalDecision = if (fixture.terminalEvidence.isEmpty()) null else {
            val consensus = TerminalConsensus()
            var last: TerminalDecision? = null
            fixture.terminalEvidence.forEach { last = consensus.observe(it) }
            last
        }

        return ReplayResult(
            screen = screen,
            adapterName = adapter.javaClass.simpleName,
            groupsFilterFound = groupsFilterFound,
            groupDecision = groupDecision,
            finalScrollDirective = scrollDirective,
            terminalDecision = terminalDecision
        )
    }

    private fun adapterFor(packageName: String): WhatsAppAdapter = when (packageName) {
        "com.whatsapp" -> ConsumerWhatsAppAdapter()
        "com.whatsapp.w4b" -> BusinessWhatsAppAdapter()
        else -> GenericWhatsAppAdapter()
    }
}

private object ReplayCodec {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun encode(fixture: ReplayFixture): String = buildString {
        append("R7REPLAY\t1\n")
        append("CAP\t").append(fixture.capturedAt).append('\n')
        fixture.nodes.forEach { n ->
            append(listOf(
                "NODE", b64(n.viewId), b64(n.className), b64(n.textHash), b64(n.contentDescriptionHash),
                n.left, n.top, n.right, n.bottom,
                n.clickable, n.scrollable, n.selected, n.enabled, n.visibleToUser,
                n.depth, b64(n.parentSignature), n.childCount, b64(n.packageName), n.windowId, n.capturedAt
            ).joinToString("\t")).append('\n')
        }
        fixture.groupProbe?.let { g ->
            append("GROUP\t").append(b64(g.title)).append('\t').append(b64(g.packageName))
            g.visibleLabels.forEach { append('\t').append(b64(it)) }
            append('\n')
        }
        fixture.scrollDirection?.let { append("DIR\t").append(it.name).append('\n') }
        fixture.scrollAttempts.forEach { a ->
            append(listOf(
                "ATT", a.strategy.name, a.dispatched,
                b64(a.beforeViewportSignature), b64(a.afterViewportSignature), a.telemetryAdvanced
            ).joinToString("\t")).append('\n')
        }
        fixture.terminalEvidence.forEach { t ->
            append("TERM\t").append(b64(t.viewportSignature)).append('\t')
                .append(b64n(t.firstStableKey)).append('\t').append(b64n(t.lastStableKey)).append('\t')
                .append(t.newStableKeys.sorted().joinToString(",") { b64(it) }).append('\t')
                .append(t.scrollAccepted).append('\t').append(t.scrollProgressed).append('\t').append(t.telemetryAdvanced)
                .append('\n')
        }
    }

    fun decode(value: String): ReplayFixture {
        var capturedAt = 0L
        val nodes = mutableListOf<NodeSnapshot>()
        var groupProbe: GroupProbeEvidence? = null
        var direction: SemanticScrollDirection? = null
        val attempts = mutableListOf<ReplayScrollAttempt>()
        val terminals = mutableListOf<TerminalEvidence>()
        val lines = value.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == "R7REPLAY\t1") { "unsupported replay fixture" }
        lines.drop(1).forEach { line ->
            val p = line.split('\t')
            when (p[0]) {
                "CAP" -> capturedAt = p[1].toLong()
                "NODE" -> nodes += NodeSnapshot(
                    viewId = ub64(p[1]), className = ub64(p[2]), textHash = ub64(p[3]), contentDescriptionHash = ub64(p[4]),
                    left = p[5].toInt(), top = p[6].toInt(), right = p[7].toInt(), bottom = p[8].toInt(),
                    clickable = p[9].toBoolean(), scrollable = p[10].toBoolean(), selected = p[11].toBoolean(),
                    enabled = p[12].toBoolean(), visibleToUser = p[13].toBoolean(), depth = p[14].toInt(),
                    parentSignature = ub64(p[15]), childCount = p[16].toInt(), packageName = ub64(p[17]),
                    windowId = p[18].toInt(), capturedAt = p[19].toLong()
                )
                "GROUP" -> groupProbe = GroupProbeEvidence(ub64(p[1]), p.drop(3).map(::ub64), ub64(p[2]))
                "DIR" -> direction = SemanticScrollDirection.valueOf(p[1])
                "ATT" -> attempts += ReplayScrollAttempt(
                    ScrollStrategy.valueOf(p[1]), p[2].toBoolean(), ub64(p[3]), ub64(p[4]), p[5].toBoolean()
                )
                "TERM" -> terminals += TerminalEvidence(
                    viewportSignature = ub64(p[1]),
                    firstStableKey = ub64n(p[2]),
                    lastStableKey = ub64n(p[3]),
                    newStableKeys = if (p[4].isBlank()) emptySet() else p[4].split(',').map(::ub64).toSet(),
                    scrollAccepted = p[5].toBoolean(),
                    scrollProgressed = p[6].toBoolean(),
                    telemetryAdvanced = p[7].toBoolean()
                )
            }
        }
        return ReplayFixture(capturedAt, nodes, groupProbe, direction, attempts, terminals)
    }

    private fun b64(value: String): String = enc.encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun ub64(value: String): String = String(dec.decode(value), Charsets.UTF_8)
    private fun b64n(value: String?): String = value?.let(::b64) ?: "~"
    private fun ub64n(value: String): String? = if (value == "~") null else ub64(value)
}
