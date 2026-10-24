package app.qcoldthrone.mobile.outbound.config

import app.qcoldthrone.mobile.outbound.Outbound
import app.qcoldthrone.mobile.outbound.OutboundFactory
import app.qcoldthrone.mobile.outbound.types.AutoSelector
import app.qcoldthrone.mobile.outbound.types.Custom
import java.util.EnumMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** AutoSelectorSkip (AutoSelectorPlan.h:12-27) with the texts of AutoSelectorSkipReason (AutoSelectorPlan.cpp:225-243). */
enum class AutoSelectorSkip(@JvmField val reason: String) {
    Missing("missing profile"),
    MetaType("chain or auto selector"),
    CoreTransitions("needs too many core switches"),
    ExtraCore("extra-core profile"),
    FullConfig("full config profile"),
    Malformed("config does not parse"),
    Tailscale("Tailscale profile"),
    ManagementEndpoint("OpenVPN or OpenConnect profile"),
    NameFilter("filtered out by name"),
    CountryFilter("filtered out by country"),
    Unavailable("last test failed"),
    XrayFullChained("Xray full config cannot be combined with the group's proxies"),

    /** Android only: a stored type this build cannot generate. */
    Unsupported("type this build cannot use"),
}

/** One profile of the tracked group as the plan reads it: the parsed outbound and the stored test columns. */
class SelectorMember(
    @JvmField val id: Long,
    /** Null when the row is gone. */
    @JvmField val outbound: Outbound?,
    /** 0 untested, ms, -1 failed, -2 connect-only. */
    @JvmField val latency: Int,
    /** Unix seconds of [latency], 0 = never. */
    @JvmField val latencyAt: Long,
    /** ISO code of the last IP test, "" = unknown. */
    @JvmField val testCountry: String,
)

/** GroupsRepo::GetGroup with Group::Profiles(): the members in list order and the resolved landing / front proxy. */
class SelectorGroup(
    @JvmField val id: Long,
    @JvmField val name: String,
    /** -1 (any id <= 0) = none. */
    @JvmField val landingProxyId: Long,
    @JvmField val frontProxyId: Long,
    /** Null when unset or gone. */
    @JvmField val landingProxy: Outbound?,
    @JvmField val frontProxy: Outbound?,
    @JvmField val members: List<SelectorMember>,
)

/** The storage the auto-selector plan reads: the tracked group, or null when it does not exist. */
fun interface SelectorStore {
    fun group(gid: Long): SelectorGroup?

    companion object {
        @JvmField
        val NONE = SelectorStore { null }
    }
}

/** AutoSelectorPlan (AutoSelectorPlan.h:31-58). */
class AutoSelectorPlan internal constructor() {
    /** Eligible members, best first, capped at pool_cap. */
    var pool: List<Long> = emptyList()
        internal set

    /** The prefix of [pool] that enters the config. */
    var build: List<Long> = emptyList()
        internal set

    var membersInGroup = 0
        internal set
    var eligible = 0
        internal set

    /** Eligible members whose fresh result will be reused. */
    var rankedByTest = 0
        internal set

    /** Failed-last-test members kept anyway, because excluding them would have emptied the pool. */
    var keptUnavailable = 0
        internal set

    /** Skip reason and count, in [AutoSelectorSkip] order. */
    var skipped: List<Pair<AutoSelectorSkip, Int>> = emptyList()
        internal set
    var truncated = false
        internal set
    var poolCapUsed = 0
        internal set
    var buildLimitUsed = 0
        internal set

    /** The caller should URL-test [AutoSelectorPlanner.unmeasuredCandidates] and rerank before building. */
    var needsRanking = false
        internal set

    /** Empty when the plan succeeded. */
    var error = ""
        internal set

    /** The tracked group: its members were planned against its landing / front proxy, and the build uses the same. */
    var group: SelectorGroup? = null
        internal set

    private val byId: Map<Long, SelectorMember> by lazy { group?.members?.associateBy { it.id } ?: emptyMap() }

    val ok: Boolean get() = error.isEmpty()

    fun member(id: Long): SelectorMember? = byId[id]

    /** The editor's plan summary (edit_autoselector.cpp:260-295): the error, or what would run and why. */
    fun summary(): String {
        if (error.isNotEmpty()) return error
        val lines = ArrayList<String>()
        lines.add("$eligible of $membersInGroup profiles in the group can be used; ${build.size} would run.")
        if (skipped.isNotEmpty()) {
            lines.add("Skipped: " + skipped.joinToString(", ") { (skip, count) -> "$count ${skip.reason}" } + ".")
        }
        if (keptUnavailable > 0) {
            lines.add(
                "Every profile's last test failed, which usually means the network was down rather than the servers, " +
                    "so all $keptUnavailable are kept and will be re-checked.",
            )
        }
        if (truncated) lines.add("More than $poolCapUsed profiles match, so only the best-ranked ones are kept.")
        if (rankedByTest > 0) lines.add("$rankedByTest have a recent test result that will be reused.")
        if (needsRanking) lines.add("The rest will be measured before the selector starts.")
        return lines.joinToString(" ")
    }
}

/**
 * The planning of src/configs/AutoSelectorPlan.cpp: which members of the tracked group run, in which order, and
 * whether some must be measured first. Every call normalises the selector like the desktop, except [rerank].
 * [clock] is unix seconds.
 */
class AutoSelectorPlanner @JvmOverloads constructor(
    private val store: SelectorStore,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /** PlanAutoSelector (cpp:245-284); [selectorId] is excluded from the members. */
    fun plan(selectorId: Long, selector: AutoSelector): AutoSelectorPlan {
        val plan = AutoSelectorPlan()
        selector.normalize()
        plan.poolCapUsed = selector.poolCap
        plan.buildLimitUsed = selector.buildLimit
        val now = clock()
        val group = store.group(selector.gid)
        if (group == null) {
            plan.error = "Auto selector points at a group that no longer exists"
            return plan
        }
        plan.group = group
        val members = eligibleMembers(selectorId, selector, group, now, plan)
        plan.eligible = members.size
        if (members.isEmpty()) {
            plan.error = "Auto selector has no usable members"
            return plan
        }

        var ordered = orderMembers(members, selector.pool, selector, group, now)
        plan.truncated = ordered.size > selector.poolCap
        if (plan.truncated) ordered = ordered.take(selector.poolCap)
        plan.pool = ordered
        plan.build = ordered.take(selector.buildLimit)
        if (ordered.size > selector.buildLimit) {
            val byId = group.members.associateBy { it.id }
            plan.needsRanking = plan.build.any { effectiveLatency(byId[it], selector, now) == 0 }
        }
        return plan
    }

    /** AutoSelectorRankingCandidates (cpp:286-293): every eligible member in group order. */
    fun rankingCandidates(selectorId: Long, selector: AutoSelector): List<Long> {
        selector.normalize()
        val group = store.group(selector.gid) ?: return emptyList()
        return eligibleMembers(selectorId, selector, group, clock(), null)
    }

    /**
     * AutoSelectorUnmeasuredCandidates (cpp:295-313): the candidates without a fresh result, plus those in [stale]
     * (members whose stored result no longer reflects reality), to URL-test before [rerank].
     */
    @JvmOverloads
    fun unmeasuredCandidates(selectorId: Long, selector: AutoSelector, stale: Collection<Long> = emptyList()): List<Long> {
        selector.normalize()
        val group = store.group(selector.gid) ?: return emptyList()
        val now = clock()
        val byId = group.members.associateBy { it.id }
        val staleSet = stale.toHashSet()
        return eligibleMembers(selectorId, selector, group, now, null).filter {
            it in staleSet || effectiveLatency(byId[it], selector, now) == 0
        }
    }

    /**
     * RerankAutoSelectorPool (cpp:315-331): every eligible member by effective latency, capped at pool_cap, written
     * into [AutoSelector.pool] / [AutoSelector.poolRankedAt]. The caller saves the profile.
     */
    fun rerank(selectorId: Long, selector: AutoSelector): List<Long> {
        val group = store.group(selector.gid)
        val now = clock()
        var members = if (group == null) emptyList() else {
            eligibleMembers(selectorId, selector, group, now, null).sortedWith(byLatency(selector, group, now))
        }
        if (members.size > selector.poolCap) members = members.take(selector.poolCap)
        selector.pool = members.toMutableList()
        selector.poolRankedAt = clock()
        return members
    }

    private class Filters(val name: Pattern?, val countries: Set<String>)

    // buildFilters (cpp:27-39): an invalid regex filters nothing (the editor refuses to save one).
    private fun buildFilters(selector: AutoSelector): Filters {
        var name: Pattern? = null
        if (selector.nameFilter.trim().isNotEmpty()) {
            name = try {
                Pattern.compile(selector.nameFilter, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            } catch (e: PatternSyntaxException) {
                null
            }
        }
        val countries = HashSet<String>()
        for (code in selector.countryFilter.split(',')) {
            if (code.isNotEmpty()) countries.add(code.trim().uppercase())
        }
        return Filters(name, countries)
    }

    // eligibilityOf (cpp:50-82); null = eligible.
    private fun eligibilityOf(member: SelectorMember, selector: AutoSelector, filters: Filters, now: Long): AutoSelectorSkip? {
        val outbound = member.outbound ?: return AutoSelectorSkip.Missing
        val type = outbound.type
        if (type == "chain" || type == "autoselector") return AutoSelectorSkip.MetaType
        if (type == "tailscale") return AutoSelectorSkip.Tailscale
        if (type == "openvpn" || type == "openconnect") return AutoSelectorSkip.ManagementEndpoint
        if (type == "extracore" || outbound.isExtraCore()) return AutoSelectorSkip.ExtraCore
        // A known type that is still invalid is a row whose stored JSON does not parse.
        if (outbound.invalid) {
            return if (OutboundFactory.canonicalType(type) in OutboundFactory.ALL_TYPES) AutoSelectorSkip.Malformed
            else AutoSelectorSkip.Unsupported
        }
        if (type == "custom") {
            val custom = outbound as? Custom ?: return AutoSelectorSkip.Missing
            // Only a sing-box full config is excluded; an Xray one gets its own instance.
            if (custom.subtype != Custom.CUSTOM_OUTBOUND && custom.subtype != Custom.CUSTOM_XRAY_OUTBOUND &&
                custom.subtype != Custom.CUSTOM_XRAY_FULL_CONFIG
            ) return AutoSelectorSkip.FullConfig
            if (custom.configObject().isEmpty()) return AutoSelectorSkip.Malformed
        }
        if (filters.name != null && !filters.name.matcher(outbound.displayName()).find()) return AutoSelectorSkip.NameFilter
        if (filters.countries.isNotEmpty() && member.testCountry.uppercase() !in filters.countries) {
            return AutoSelectorSkip.CountryFilter
        }
        // Only a fresh failure excludes a member; an old one would exile it permanently.
        if (selector.excludeUnavailable && member.latency < 0 && hasFreshResult(member, selector, now)) {
            return AutoSelectorSkip.Unavailable
        }
        return null
    }

    // eligibleMembers (cpp:139-198) against the given group; [plan] collects the counters when set.
    private fun eligibleMembers(
        selectorId: Long, selector: AutoSelector, group: SelectorGroup, now: Long, plan: AutoSelectorPlan?,
    ): List<Long> {
        val filters = buildFilters(selector)
        val skips = EnumMap<AutoSelectorSkip, Int>(AutoSelectorSkip::class.java)
        val members = ArrayList<Long>()
        val unavailable = ArrayList<Long>()
        for (member in group.members) {
            if (member.id == selectorId) continue
            if (plan != null) plan.membersInGroup++
            val skip = eligibilityOf(member, selector, filters, now)
            if (skip != null && skip != AutoSelectorSkip.Unavailable) {
                skips.merge(skip, 1, Int::plus)
                continue
            }
            val outbound = member.outbound ?: continue
            if (outbound.isXrayFullConfig()) {
                if (!xrayFullConfigFitsChain(group.landingProxy, group.frontProxy)) {
                    skips.merge(AutoSelectorSkip.XrayFullChained, 1, Int::plus)
                    continue
                }
            } else if (coreTransitions(group.landingProxy, outbound, group.frontProxy) > 2) {
                skips.merge(AutoSelectorSkip.CoreTransitions, 1, Int::plus)
                continue
            }
            if (skip == AutoSelectorSkip.Unavailable) {
                unavailable.add(member.id)
                continue
            }
            members.add(member.id)
            if (plan != null && effectiveLatency(member, selector, now) > 0) plan.rankedByTest++
        }
        // Every member's last test failing is an outage, not a dead subscription: excluding them all would leave
        // nothing that could discover the servers came back.
        if (members.isEmpty() && unavailable.isNotEmpty()) {
            members.addAll(unavailable)
            if (plan != null) plan.keptUnavailable = unavailable.size
        } else if (unavailable.isNotEmpty()) {
            skips.merge(AutoSelectorSkip.Unavailable, unavailable.size, Int::plus)
        }
        if (plan != null) plan.skipped = skips.entries.map { it.key to it.value }
        return members
    }

    // orderMembers (cpp:200-222): the persisted pool is the core's ranking prior; newcomers follow by latency.
    private fun orderMembers(
        members: List<Long>, persistedPool: List<Long>, selector: AutoSelector, group: SelectorGroup, now: Long,
    ): List<Long> {
        val eligible = members.toHashSet()
        val ordered = ArrayList<Long>(members.size)
        val placed = HashSet<Long>()
        for (id in persistedPool) {
            if (id !in eligible || !placed.add(id)) continue
            ordered.add(id)
        }
        ordered.addAll(members.filter { it !in placed }.sortedWith(byLatency(selector, group, now)))
        return ordered
    }

    // byLatency (cpp:128-137) as a stable-sort comparator: fresh ok ascending, then untested, then failed.
    private fun byLatency(selector: AutoSelector, group: SelectorGroup, now: Long): Comparator<Long> {
        val byId = group.members.associateBy { it.id }
        return Comparator { left, right ->
            val leftLatency = effectiveLatency(byId[left], selector, now)
            val rightLatency = effectiveLatency(byId[right], selector, now)
            val leftRank = latencyRank(leftLatency)
            val rightRank = latencyRank(rightLatency)
            when {
                leftRank != rightRank -> leftRank.compareTo(rightRank)
                leftRank == 0 -> leftLatency.compareTo(rightLatency)
                else -> 0
            }
        }
    }

    companion object {
        /** hasFreshResult (cpp:41-48): a stored result inside the trust window; rows without a timestamp are stale. */
        @JvmStatic
        fun hasFreshResult(member: SelectorMember?, selector: AutoSelector, now: Long): Boolean {
            if (member == null || member.latency == 0) return false
            if (selector.resultValidityMins <= 0) return false
            if (member.latencyAt <= 0) return false
            return now - member.latencyAt <= selector.resultValidityMins.toLong() * 60
        }

        /** effectiveLatency (cpp:118-121): stale results count as untested (0). */
        @JvmStatic
        fun effectiveLatency(member: SelectorMember?, selector: AutoSelector, now: Long): Int =
            if (member != null && hasFreshResult(member, selector, now)) member.latency else 0

        // latencyRank (cpp:110-116): untested (1) outranks failed (2) so a fresh subscription still gets explored.
        private fun latencyRank(latency: Int): Int = when {
            latency > 0 -> 0
            latency == 0 -> 1
            else -> 2
        }

        // coreTransitions (cpp:84-98): a chain may hand off sing-box <-> Xray at most twice.
        private fun coreTransitions(landing: Outbound?, member: Outbound, front: Outbound?): Int {
            var transitions = 0
            var inXray = false
            for (hop in listOf(landing, member, front)) {
                if (hop == null) continue
                val xray = hop.isXray()
                if (xray != inXray) transitions++
                inXray = xray
            }
            return transitions
        }

        // xrayFullConfigFitsChain (cpp:100-108): chainScanError's rules for a full-config member.
        private fun xrayFullConfigFitsChain(landing: Outbound?, front: Outbound?): Boolean {
            if (front != null) return false
            if (landing == null) return true
            return !landing.isXray() && !landing.isXrayFullConfig() && !landing.isExtraCore()
        }
    }
}
