package app.qcoldthrone.mobile.bg.autoselector

import app.qcoldthrone.mobile.bg.test.TestEngine
import app.qcoldthrone.mobile.database.ProfileManager
import app.qcoldthrone.mobile.database.ProxyEntity
import app.qcoldthrone.mobile.database.SagerDatabase
import app.qcoldthrone.mobile.database.SettingsMapper
import app.qcoldthrone.mobile.ktx.Logs
import app.qcoldthrone.mobile.outbound.config.AutoSelectorPlanner
import app.qcoldthrone.mobile.outbound.types.AutoSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Auto-selector profiles in storage: load / save, and the ranking of mainwindow_autoselector.cpp. */
object AutoSelectorProfiles {

    const val TYPE = "autoselector"

    /** A fresh parse of the stored selector [id]; null when it is gone or not a selector. */
    fun load(id: Long): AutoSelector? = ProfileManager.getProfile(id)?.outbound as? AutoSelector

    /** Writes the selector's JSON only, so traffic and test results written meanwhile stay. */
    fun save(id: Long, selector: AutoSelector) {
        SagerDatabase.proxyDao.updateOutbound(id, selector.type, selector.name, selector.exportToJson().toCompact())
    }

    /** ProfilesRepo::GetProfileIdsByType("autoselector"). */
    fun ids(): List<Long> {
        val ids = ArrayList<Long>()
        SagerDatabase.instance.query("SELECT `id` FROM `${ProxyEntity.TABLE}` WHERE `type` = ?", arrayOf(TYPE)).use {
            while (it.moveToNext()) ids.add(it.getLong(0))
        }
        return ids
    }

    /** The ids among [ids] that still exist. */
    fun existing(ids: Collection<Long>): Set<Long> =
        if (ids.isEmpty()) emptySet() else ProfileManager.getProfiles(ids.distinct()).mapTo(HashSet()) { it.id }

    /**
     * AutoSelectorMonitor::PersistHealth (AutoSelectorMonitor.cpp:104-135): a probed member's average becomes its
     * profile's latency, a dead one's -1 with the core's last error as the test error; softer states are inconclusive
     * and keep their result. Only changed latencies are written, and only the latency result: traffic and the other
     * test columns stay. [profileIds] maps the members' tags to profiles.
     */
    fun saveHealth(profileIds: Map<String, Long>, members: List<AutoSelectorStatus.Member>) {
        val at = System.currentTimeMillis() / 1000
        val dao = SagerDatabase.proxyDao
        SagerDatabase.instance.runInTransaction {
            for (member in members) {
                val id = profileIds[member.tag] ?: continue
                if (member.probes == 0) continue
                when {
                    member.isUsable && member.averageMs > 0 -> dao.updateLatency(id, member.averageMs, at, null)
                    member.isDead -> dao.updateLatency(id, -1, at, member.lastError.ifEmpty { null })
                }
            }
        }
    }

    /** profile_start (mainwindow_profile_lifecycle.cpp:206-221): the plan succeeds and wants measurements first. */
    fun needsRanking(id: Long): Boolean {
        val selector = load(id) ?: return false
        val plan = AutoSelectorPlanner(SettingsMapper.selectorStore()).plan(id, selector)
        return plan.ok && plan.needsRanking
    }

    /**
     * rank_auto_selector (mainwindow_autoselector.cpp:13-31): URL-tests the candidates without a fresh result plus
     * [stale] (waiting for any running test session), then reranks and saves `pool` / `pool_ranked_at`.
     * [onMeasuring] runs before the tests with their count. Returns the ranked pool size.
     */
    suspend fun rank(id: Long, stale: Collection<Long> = emptyList(), onMeasuring: suspend (Int) -> Unit = {}): Int {
        val needed = withContext(Dispatchers.IO) {
            val selector = load(id) ?: return@withContext null
            AutoSelectorPlanner(SettingsMapper.selectorStore()).unmeasuredCandidates(id, selector, stale)
        } ?: return 0
        if (needed.isNotEmpty()) {
            Logs.i("[Auto selector] Measuring ${needed.size} not-yet-tested profiles...")
            onMeasuring(needed.size)
            TestEngine.queueUrlTests(needed)
        }
        return withContext(Dispatchers.IO) {
            // Re-read: the selector may have been saved while the tests ran, and the store must see the new results.
            val selector = load(id) ?: return@withContext 0
            val ranked = AutoSelectorPlanner(SettingsMapper.selectorStore()).rerank(id, selector)
            save(id, selector)
            if (needed.isEmpty()) {
                Logs.i("[Auto selector] Reusing existing test results; ranked ${ranked.size} profiles.")
            } else {
                Logs.i("[Auto selector] Ranked ${ranked.size} profiles.")
            }
            ranked.size
        }
    }
}
