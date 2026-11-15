package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock

class TrafficUpdater(
    private val box: libcore.BoxInstance,
    val items: List<TrafficLooperData>, // contain "bypass"
) {

    class TrafficLooperData(
        // Don't associate proxyEntity
        var tag: String,
        var tx: Long = 0,
        var rx: Long = 0,
        var txBase: Long = 0,
        var rxBase: Long = 0,
        var txRate: Long = 0,
        var rxRate: Long = 0,
        var lastUpdate: Long = 0,
        var ignore: Boolean = false,
    )

    private class TagStatsDelta(
        var tx: Long = 0L,
        var rx: Long = 0L,
        var txRate: Long = 0L,
        var rxRate: Long = 0L,
    )

    private val scratchDeltas = HashMap<String, TagStatsDelta>(items.size)

    private fun updateTagDelta(item: TrafficLooperData, delta: TagStatsDelta, nowElapsedMs: Long) {
        val prevUpdate = item.lastUpdate
        item.lastUpdate = nowElapsedMs
        val interval = if (prevUpdate > 0L) nowElapsedMs - prevUpdate else 0L
        if (interval <= 0L) {
            item.rxRate = 0L
            item.txRate = 0L
            delta.tx = 0L
            delta.rx = 0L
            delta.txRate = 0L
            delta.rxRate = 0L
            return
        }

        val tx = box.queryStats(item.tag, "uplink")
        val rx = box.queryStats(item.tag, "downlink")
        val rxRate = rx * 1000L / interval
        val txRate = tx * 1000L / interval

        item.rx += rx
        item.tx += tx
        item.rxRate = rxRate
        item.txRate = txRate

        delta.tx = tx
        delta.rx = rx
        delta.txRate = txRate
        delta.rxRate = rxRate
    }

    suspend fun updateAll() {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        scratchDeltas.forEach { (_, d) ->
            d.tx = 0L
            d.rx = 0L
            d.txRate = 0L
            d.rxRate = 0L
        }
        val activeTags = HashSet<String>(items.size)
        for (item in items) {
            if (item.ignore) continue
            val delta = scratchDeltas.getOrPut(item.tag) { TagStatsDelta() }
            if (activeTags.add(item.tag)) {
                updateTagDelta(item, delta, nowElapsedMs)
            } else {
                item.lastUpdate = nowElapsedMs
                item.rx += delta.rx
                item.tx += delta.tx
                item.rxRate = delta.rxRate
                item.txRate = delta.txRate
            }
        }
    }
}
