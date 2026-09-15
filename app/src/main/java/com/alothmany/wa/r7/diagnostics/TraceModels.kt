package com.alothmany.wa.r7.diagnostics

import java.util.ArrayDeque

data class TraceEvent(
    val runId: String,
    val operationId: String,
    val queueItemId: String?,
    val state: String,
    val actionId: String?,
    val beforeObservationSignature: String?,
    val afterObservationSignature: String?,
    val verificationResult: String?,
    val recoveryReason: String?,
    val adapterProfile: String?,
    val timestamp: Long
)

class TraceRecorder(private val capacity: Int = 500) {
    init { require(capacity > 0) }
    private val buffer = ArrayDeque<TraceEvent>(capacity)

    @Synchronized fun record(event: TraceEvent) {
        while (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(event)
    }

    @Synchronized fun snapshot(): List<TraceEvent> = buffer.toList()
}

data class RuntimeMetricSample(
    val runId: String,
    val capturedAt: Long,
    val pssKb: Long,
    val eventsPerMinute: Int,
    val snapshotsPerMinute: Int,
    val actionsPerMinute: Int,
    val verificationFailures: Int,
    val recoveries: Int,
    val fallbacks: Int,
    val dbBatches: Int,
    val staleCallbacks: Int,
    val circuitBreakerActivations: Int
)

class MetricRecorder(private val capacity: Int = 240) {
    init { require(capacity > 0) }
    private val buffer = ArrayDeque<RuntimeMetricSample>(capacity)

    @Synchronized fun sample(sample: RuntimeMetricSample) {
        while (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(sample)
    }

    @Synchronized fun snapshot(): List<RuntimeMetricSample> = buffer.toList()
}
