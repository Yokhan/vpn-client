package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_DIRECT
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.*

class TrafficLooper
    (
    val data: BaseService.Data, private val sc: CoroutineScope
) {

    private var job: Job? = null
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>() // id to 1 data (selector)
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>() // tag to 1 data
    private val idTagsMap = mutableMapOf<Long, MutableList<TrafficUpdater.TrafficLooperData>>()
    private val idBaseTraffic = mutableMapOf<Long, Pair<Long, Long>>() // id -> (rxBase, txBase)

    suspend fun stop() {
        job?.cancel()
        // finally traffic post
        if (!DataStore.profileTrafficStatistics) return
        val traffic = mutableMapOf<Long, TrafficData>()
        data.proxy?.config?.trafficMap?.forEach { (_, ents) ->
            for (ent in ents) {
                if (traffic.containsKey(ent.id)) continue
                val itemsForEnt = idTagsMap[ent.id] ?: idMap[ent.id]?.let(::listOf) ?: continue
                val base = idBaseTraffic[ent.id] ?: (ent.rx to ent.tx)
                ent.rx = base.first + itemsForEnt.sumOf { it.rx - it.rxBase }
                ent.tx = base.second + itemsForEnt.sumOf { it.tx - it.txBase }
                withContext(Dispatchers.IO) {
                    ProfileManager.updateProfile(ent) // update DB
                }
                traffic[ent.id] = TrafficData(
                    id = ent.id,
                    rx = ent.rx,
                    tx = ent.tx,
                )
            }
        }
        data.binder.broadcast { b ->
            for (t in traffic) {
                b.cbTrafficUpdate(t.value)
            }
        }
        Logs.d("finally traffic post done")
    }

    fun start() {
        job = sc.launch { loop() }
    }

    var selectorNowId = -114514L
    var selectorNowFakeTag = ""

    fun selectMain(id: Long) {
        Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
        val oldData = idMap[selectorNowId]
        val newData = idMap[id] ?: return
        oldData?.apply {
            tag = selectorNowFakeTag
            ignore = true
            // post traffic when switch
            if (DataStore.profileTrafficStatistics) {
                data.proxy?.config?.trafficMap?.get(tag)?.firstOrNull()?.let {
                    it.rx = rx
                    it.tx = tx
                    runOnDefaultDispatcher {
                        ProfileManager.updateProfile(it) // update DB
                    }
                }
            }
        }
        selectorNowFakeTag = newData.tag
        selectorNowId = id
        newData.apply {
            tag = TAG_PROXY
            ignore = false
        }
    }

    private suspend fun loop() {
        val delayMs = DataStore.speedInterval.toLong().let { if (it <= 0L) 1000L else it }
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics

        var trafficUpdater: TrafficUpdater? = null
        var proxy: ProxyInstance?

        // for display
        val itemBypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)
        val itemDirect = TrafficUpdater.TrafficLooperData(tag = TAG_DIRECT)
        var lastBroadcastSpeed: SpeedDisplayData? = null
        val lastBroadcastTraffic = HashMap<Long, Pair<Long, Long>>()

        while (sc.isActive) {
            proxy = data.proxy
            if (proxy == null) {
                delay(delayMs)
                continue
            }

            if (trafficUpdater == null) {
                if (!proxy.isInitialized()) {
                    delay(100L)
                    continue
                }
                idMap.clear()
                tagMap.clear()
                idTagsMap.clear()
                idBaseTraffic.clear()
                val allItems = mutableListOf<TrafficUpdater.TrafficLooperData>(itemBypass)
                val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
                proxy.config.trafficMap.forEach { (tag, ents) ->
                    tags.add(tag)
                    for (ent in ents) {
                        idBaseTraffic.putIfAbsent(ent.id, ent.rx to ent.tx)
                        val item = TrafficUpdater.TrafficLooperData(
                            tag = tag,
                            rx = ent.rx,
                            tx = ent.tx,
                            rxBase = ent.rx,
                            txBase = ent.tx,
                            ignore = proxy.config.selectorGroupId >= 0L,
                        )
                        idMap.putIfAbsent(ent.id, item)
                        idTagsMap.getOrPut(ent.id) { mutableListOf() }.add(item)
                        tagMap[tag] = item
                        allItems.add(item)
                        Logs.d("traffic count $tag to ${ent.id}")
                    }
                }
                if (TAG_DIRECT !in tagMap) {
                    tags.add(TAG_DIRECT)
                    allItems.add(itemDirect)
                }
                if (proxy.config.selectorGroupId >= 0L) {
                    selectMain(proxy.config.mainEntId)
                }
                //
                trafficUpdater = TrafficUpdater(
                    box = proxy.box, items = allItems
                )
                proxy.box.setV2rayStats(tags.joinToString("\n"))
            }

            trafficUpdater.updateAll()
            if (!sc.isActive) return

            // add all non-bypass to "main"
            var mainTxRate = 0L
            var mainRxRate = 0L
            var mainTx = 0L
            var mainRx = 0L
            tagMap.forEach { (_, it) ->
                if (!it.ignore) {
                    mainTxRate += it.txRate
                    mainRxRate += it.rxRate
                }
                mainTx += it.tx - it.txBase
                mainRx += it.rx - it.rxBase
            }
            val directTxRate = itemBypass.txRate + if (TAG_DIRECT !in tagMap) itemDirect.txRate else 0L
            val directRxRate = itemBypass.rxRate + if (TAG_DIRECT !in tagMap) itemDirect.rxRate else 0L

            // speed
            val speed = SpeedDisplayData(
                mainTxRate,
                mainRxRate,
                if (showDirectSpeed) directTxRate else 0L,
                if (showDirectSpeed) directRxRate else 0L,
                mainTx,
                mainRx
            )

            // broadcast (MainActivity) only on actual changes to minimize AIDL IPC overhead
            val speedChanged = lastBroadcastSpeed?.let { prev ->
                prev.txRateProxy != speed.txRateProxy ||
                    prev.rxRateProxy != speed.rxRateProxy ||
                    prev.txRateDirect != speed.txRateDirect ||
                    prev.rxRateDirect != speed.rxRateDirect ||
                    prev.txTotal != speed.txTotal ||
                    prev.rxTotal != speed.rxTotal
            } ?: true
            if (data.state == BaseService.State.Connected
                && data.binder.callbackIdMap.containsValue(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            ) {
                data.binder.broadcast { b ->
                    if (data.binder.callbackIdMap[b] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        if (speedChanged) {
                            b.cbSpeedUpdate(speed)
                        }
                        if (profileTrafficStatistics) {
                            idTagsMap.forEach { (id, itemsForEnt) ->
                                val base = idBaseTraffic[id] ?: (0L to 0L)
                                val totalRx = base.first + itemsForEnt.sumOf { it.rx - it.rxBase }
                                val totalTx = base.second + itemsForEnt.sumOf { it.tx - it.txBase }
                                val prev = lastBroadcastTraffic[id]
                                if (prev == null || prev.first != totalRx || prev.second != totalTx) {
                                    lastBroadcastTraffic[id] = totalRx to totalTx
                                    b.cbTrafficUpdate(
                                        TrafficData(id = id, rx = totalRx, tx = totalTx) // display
                                    )
                                }
                            }
                        }
                    }
                }
                if (speedChanged) {
                    lastBroadcastSpeed = speed
                }
            }

            // ServiceNotification
            if (speedChanged) {
                data.notification?.apply {
                    if (listenPostSpeed) postNotificationSpeedUpdate(speed)
                }
            }

            delay(delayMs)
        }
    }
}
