package com.spaces.daemon

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable

@Serializable
data class PerfMetricSnapshot(
        val name: String,
        val count: Long,
        val totalMs: Double,
        val maxMs: Double
)

object PerfStats {
    private val enabled = (System.getenv("SPACES_PERF_TRACE") ?: "0") == "1"
    private val metrics = ConcurrentHashMap<String, PerfMetric>()

    fun isEnabled(): Boolean = enabled

    fun observe(name: String, durationNanos: Long) {
        if (!enabled) return
        val metric = metrics.computeIfAbsent(name) { PerfMetric() }
        metric.count.incrementAndGet()
        metric.totalNanos.addAndGet(durationNanos)
        metric.updateMax(durationNanos)
    }

    fun <T> timed(name: String, block: () -> T): T {
        if (!enabled) return block()
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            observe(name, System.nanoTime() - start)
        }
    }

    fun snapshot(): List<PerfMetricSnapshot> {
        if (!enabled) return emptyList()
        return metrics.entries
                .map { (name, metric) ->
                    PerfMetricSnapshot(
                            name = name,
                            count = metric.count.get(),
                            totalMs = metric.totalNanos.get() / 1_000_000.0,
                            maxMs = metric.maxNanos.get() / 1_000_000.0
                    )
                }
                .sortedByDescending { it.totalMs }
    }

    fun reset() {
        metrics.clear()
    }

    private class PerfMetric {
        val count = AtomicLong(0)
        val totalNanos = AtomicLong(0)
        val maxNanos = AtomicLong(0)

        fun updateMax(durationNanos: Long) {
            while (true) {
                val current = maxNanos.get()
                if (durationNanos <= current) return
                if (maxNanos.compareAndSet(current, durationNanos)) return
            }
        }
    }
}
