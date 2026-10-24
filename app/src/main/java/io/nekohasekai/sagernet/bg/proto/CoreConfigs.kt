package app.qcoldthrone.mobile.bg.proto

import app.qcoldthrone.mobile.database.ProxyEntity
import app.qcoldthrone.mobile.database.ProxyGroup
import app.qcoldthrone.mobile.database.RouteManager
import app.qcoldthrone.mobile.database.SagerDatabase
import app.qcoldthrone.mobile.database.SettingsMapper
import app.qcoldthrone.mobile.outbound.Outbound
import app.qcoldthrone.mobile.outbound.config.ConfigGenerator
import app.qcoldthrone.mobile.outbound.config.GeneratedConfig
import app.qcoldthrone.mobile.outbound.config.GeneratorSettings
import app.qcoldthrone.mobile.outbound.config.ProfileProvider
import app.qcoldthrone.mobile.outbound.config.RoutingInput
import app.qcoldthrone.mobile.outbound.config.TestCandidate
import app.qcoldthrone.mobile.route.RouteProfile

/**
 * The config generator wired to the app: profiles come from the profile table, the settings and the build-time
 * globals from DataStore through [SettingsMapper], the current route profile and the rule-set list from
 * RouteManager (main configs only; test configs never read them), the landing / front proxy from the profile's group.
 */
object CoreConfigs {

    /** Stored profiles by id, each row parsed once per generation. */
    private class DatabaseProfiles : ProfileProvider {
        private val cache = HashMap<Long, Outbound?>()
        override fun get(id: Long): Outbound? = cache.getOrPut(id) { SagerDatabase.proxyDao.getById(id)?.outbound }
    }

    fun generator(
        routing: RoutingInput = RoutingInput.DEFAULT,
        settings: GeneratorSettings = SettingsMapper.generatorSettings(),
    ): ConfigGenerator = ConfigGenerator(DatabaseProfiles(), settings, SettingsMapper.buildContext(), routing)

    /**
     * The main config of [profile]; throws with the generator's message when it cannot be built. [deferRuleSets]
     * is GeneratorSettings.deferRuleSets, for a start the user let go without its rule-sets.
     */
    fun buildMain(profile: ProxyEntity, deferRuleSets: Boolean = false): GeneratedConfig {
        val (landing, front) = groupProxies(profile.groupId)
        var settings = SettingsMapper.generatorSettings().copy(deferRuleSets = deferRuleSets)
        val options = SagerDatabase.groupDao.getById(profile.groupId)?.subOptions
        val bound = options?.takeIf { it.routingProvider.isNotBlank() && it.providerRoutingEnabled }
        val route = when {
            bound?.providerRouting == "off" -> app.qcoldthrone.mobile.route.RouteProfile.defaultProfile()
            bound != null -> app.qcoldthrone.mobile.route.ProviderRouting.profile(
                app.qcoldthrone.mobile.outbound.json.JsonInput.parseObjectOrNull(bound.providerRouting) ?: app.qcoldthrone.mobile.outbound.json.JsonObject(), bound.routingProvider)
            else -> app.qcoldthrone.mobile.database.RouteManager.current().copy()
        }
        var routing = SettingsMapper.routingInput(route)
        val fullJson = profile.outbound.isXrayFullConfig()
        val providerRouting=app.qcoldthrone.mobile.route.ProviderRouting.stored(route)
        providerRouting?.let { (_, document) ->
            if (fullJson) {
                val custom = profile.outbound as? app.qcoldthrone.mobile.outbound.types.Custom
                if (custom != null) app.qcoldthrone.mobile.route.ProviderGeoAssets.installXray(document,app.qcoldthrone.mobile.bg.XrayGeoAssets.needed(listOf(custom.config)))
                val tunnel = app.qcoldthrone.mobile.route.ProviderRouting.settings(document,settings)
                settings = settings.copy(remoteDns = tunnel.remoteDns, dnsPredefinedRules = tunnel.dnsPredefinedRules, fakeDns = false)
                routing = SettingsMapper.routingInput(app.qcoldthrone.mobile.route.RouteProfile.defaultProfile())
            } else {
                settings = app.qcoldthrone.mobile.route.ProviderRouting.settings(document, settings)
                val catalog = app.qcoldthrone.mobile.route.ProviderGeoAssets.resolve(route, document, routing.catalog)
                routing = RoutingInput(route, catalog, routing.selectors, routing.memberCheck)
            }
        }
        val profiles = DatabaseProfiles()
        var context = SettingsMapper.buildContext()
        if (app.qcoldthrone.mobile.outbound.config.TransportIpPolicy.ipv4Only(profile.outbound, profiles)) {
            settings = app.qcoldthrone.mobile.outbound.config.TransportIpPolicy.settings(settings)
            context = app.qcoldthrone.mobile.outbound.config.TransportIpPolicy.context(context)
            moe.matsuri.nb4a.utils.CoreLog.write("[Info] ${profile.outbound.displayType()} uses IPv4-only tunnel and DNS; saved IPv6 preference is unchanged")
        }
        moe.matsuri.nb4a.utils.CoreLog.write("[Info] "+DnsDiagnostics.summary(settings,providerRouting!=null))
        val generated = ConfigGenerator(profiles, settings, context, routing).build(profile.id, landing, front)
        if (!generated.ok) error(generated.error ?: "config generation failed")
        return generated
    }

    /** One test config for every candidate at once, each under its own group's landing / front proxy. */
    fun buildTest(profileIds: List<Long>): GeneratedConfig {
        val groups = HashMap<Long, Pair<Long, Long>>()
        val candidates = profileIds.map { id ->
            val groupId = SagerDatabase.proxyDao.getById(id)?.groupId ?: -1L
            val (landing, front) = groups.getOrPut(groupId) { groupProxies(groupId) }
            TestCandidate(id, landing, front)
        }
        return generator(RoutingInput(RouteProfile.defaultProfile(), RouteManager.catalog())).buildTest(candidates)
    }

    /** (landing_proxy_id, front_proxy_id) of a group, -1 when unset (any id <= 0 is "none"). */
    private fun groupProxies(groupId: Long): Pair<Long, Long> {
        val group: ProxyGroup = (if (groupId > 0) SagerDatabase.groupDao.getById(groupId) else null) ?: return -1L to -1L
        val landing = group.landingProxyId.takeIf { it > 0 } ?: -1L
        val front = group.frontProxyId.takeIf { it > 0 } ?: -1L
        return landing to front
    }
}
