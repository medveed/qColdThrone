package app.qcoldthrone.mobile.bg.proto

import app.qcoldthrone.mobile.bg.AbstractInstance
import app.qcoldthrone.mobile.bg.CoreRuntime
import app.qcoldthrone.mobile.bg.SingBoxDashboard
import app.qcoldthrone.mobile.bg.XrayGeoAssets
import app.qcoldthrone.mobile.database.DataStore
import app.qcoldthrone.mobile.database.ProxyEntity
import app.qcoldthrone.mobile.ktx.Logs
import app.qcoldthrone.mobile.ktx.isLoopbackPortFree
import app.qcoldthrone.mobile.ktx.mkPort
import app.qcoldthrone.mobile.ktx.readableMessage
import app.qcoldthrone.mobile.outbound.config.AutoSelectorBuild
import app.qcoldthrone.mobile.outbound.config.GeneratedConfig
import app.qcoldthrone.mobile.outbound.types.Custom
import app.qcoldthrone.core.mobile.Instance
import app.qcoldthrone.core.mobile.Mobile
import app.qcoldthrone.core.mobile.StartOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import app.qcoldthrone.mobile.bg.carrier.NativeConnectionStatus
import app.qcoldthrone.mobile.bg.carrier.NativeConnectionStage
import app.qcoldthrone.mobile.bg.carrier.CarrierPool

abstract class BoxInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: GeneratedConfig
    lateinit var core: CoreConfig
    lateinit var box: Instance

    private val carrierProcesses = mutableListOf<app.qcoldthrone.mobile.bg.carrier.CarrierPool.Lease>()
    private var nativeStatus:NativeConnectionStatus.Writer?=null
    private var nativeWatchdog:Job?=null

    val boxOrNull: Instance? get() = if (::box.isInitialized) box else null

    /** This start does not wait for remote rule-sets the core has no copy of ([DeferredRuleSets]). */
    var deferRuleSets = false

    fun isInitialized(): Boolean {
        return ::config.isInitialized && ::box.isInitialized
    }

    protected open fun buildConfig() {
        moe.matsuri.nb4a.utils.DiagnosticLogging.prepare()
        // random_inbound_port: a fresh port per start, saved so the app's own requests reach it (mainwindow_setup.cpp:193-196).
        if (DataStore.randomInboundPort && !DataStore.lanSharingEnabled) {
            DataStore.inboundSocksPort = mkPort()
            do { DataStore.inboundHttpPort = mkPort() } while(DataStore.inboundHttpPort == DataStore.inboundSocksPort)
        }
        // The dashboard is on by default, so another app holding its port must not fail the start: it moves, and the
        // dashboard screen follows the saved port.
        if (DataStore.apiDashboardEnabled && !isLoopbackPortFree(DataStore.coreBoxApiPort)) {
            val port = mkPort()
            Logs.w("sing-box API port ${DataStore.coreBoxApiPort} is in use, the dashboard moves to $port")
            DataStore.coreBoxApiPort = port
        }
        // A custom full config keeps its own rule-sets as written.
        if (isCustomFullConfig()) deferRuleSets = false
        config = CoreConfigs.buildMain(profile, deferRuleSets)
        if(DataStore.serviceMode==app.qcoldthrone.mobile.Key.MODE_VPN) {
            app.qcoldthrone.mobile.bg.carrier.VpnListenerPolicy.check(config.coreConfig, DataStore.lanSharingEnabled)
        }
    }

    protected open suspend fun loadConfig() {
        box = Mobile.newInstance(CoreRuntime.platform, core.toStartOptions(config.autoSelector))
    }

    open suspend fun init() {
        withContext(Dispatchers.IO) { buildConfig() }
        core = CoreConfig.from(config, listOf(CoreConfig.TAG_PROXY))
        CoreRuntime.applyLogLevel(config.coreConfig)
        ensureXrayAssets()
        // Before the start: the core downloads the dashboard itself when it finds the dir empty.
        if (DataStore.apiDashboardEnabled) withContext(Dispatchers.IO) { SingBoxDashboard.install() }
        if (deferRuleSets) {
            // The sets' initial_path; without the file the core falls back to fetching during the start.
            withContext(Dispatchers.IO) {
                runCatching { DeferredRuleSets.writeEmptySet() }.onFailure { Logs.w("rule-sets: ${it.readableMessage}") }
            }
        }
        try {
            if(config.carrierPlans.any { it.kind in listOf(app.qcoldthrone.mobile.bg.carrier.CarrierKind.QWDTT,app.qcoldthrone.mobile.bg.carrier.CarrierKind.CSQTT,app.qcoldthrone.mobile.bg.carrier.CarrierKind.WDTTPLUS) }) {
                nativeStatus=NativeConnectionStatus.Writer(profile.id).also { it.update(NativeConnectionStage.PREPARING) }
            }
            for(plan in config.carrierPlans) {
                carrierProcesses.add(CarrierPool.acquire(plan,onStatus={ nativeStatus?.update(it) }))
            }
            nativeStatus?.update(NativeConnectionStage.CONNECTED)
            loadConfig()
        } catch(error:Throwable) {
            carrierProcesses.asReversed().forEach { it.close() };carrierProcesses.clear()
            throw error
        }
    }

    /** The geo asset downloads the start waits for; null once they are over. */
    protected open suspend fun onAssetProgress(progress: XrayGeoAssets.Progress?) {}

    /**
     * Fetches the data files the Xray configs about to start read (geoip: / geosite: rules) and that are missing;
     * throws [XrayGeoAssets.DownloadException] naming what could not be had.
     */
    private suspend fun ensureXrayAssets() {
        val configs = listOfNotNull(config.xrayConfig) + config.xrayFullConfigs
        if (configs.isEmpty()) return
        val missing = withContext(Dispatchers.IO) { XrayGeoAssets.missing(XrayGeoAssets.needed(configs)) }
        if (missing.isEmpty()) return
        val failures = try {
            XrayGeoAssets.ensure(missing) { onAssetProgress(it) }
        } finally {
            withContext(NonCancellable) { runCatching { onAssetProgress(null) } }
        }
        if (failures.isNotEmpty()) {
            throw XrayGeoAssets.DownloadException(
                failures.values.joinToString("\n"),
                fetchable = failures.keys.any { it in XrayGeoAssets.FILES },
            )
        }
    }

    override fun launch() {
        try {
            box.start()
        } catch (error: Throwable) {
            Logs.w("box start failed for profile ${profile.id}: ${error.message}")
            box.localDNSFailure()?.let { throw LocalDnsFailedException(it.servers, error) }
            // Not offered again by a start that went without them, nor for a custom full config.
            if (!deferRuleSets && DeferredRuleSets.isStartFailure(error.message) && !isCustomFullConfig()) {
                throw RuleSetDownloadFailedException(error)
            }
            throw error
        }
        CoreRuntime.attachRunning(box, profile.id)
        if(nativeStatus!=null) nativeWatchdog=CoroutineScope(Dispatchers.IO).launch {
            while(isActive) {
                delay(1000)
                val failure=config.carrierPlans.firstNotNullOfOrNull { CarrierPool.recoveryFailure(it) } ?: continue
                nativeStatus?.update(NativeConnectionStage.RECONNECTING)
                if(!failure.second) delay(2_000)
                withContext(Dispatchers.Main) {
                    val service=DataStore.baseService
                    if(service!=null && service.data.proxy===this@BoxInstance && service.data.state==app.qcoldthrone.mobile.bg.BaseService.State.Connected) {
                        if(failure.second) service.stopRunner(msg=failure.first)
                        else { Logs.i("Native transport watchdog: reconnecting");app.qcoldthrone.mobile.SagerNet.reloadService() }
                    }
                }
                break
            }
        }
        if (deferRuleSets) DeferredRuleSets.afterStart(box, config.coreConfig)
    }

    private fun isCustomFullConfig(): Boolean = (profile.outbound as? Custom)?.isFullConfig() == true

    override fun close() {
        nativeWatchdog?.cancel();nativeWatchdog=null
        nativeStatus?.close();nativeStatus=null
        boxOrNull?.let(CoreRuntime::detachRunning)
        try { boxOrNull?.close() } finally {
            carrierProcesses.asReversed().forEach { it.close() };carrierProcesses.clear()
        }
    }

}

/** A start that failed on the local DNS server; [servers] are the network's DNS servers it asked, empty for Android's resolver. */
class LocalDnsFailedException(val servers: String, cause: Throwable) : Exception(cause.message, cause)

/** A start that failed on a remote rule-set without a cached copy that could not be downloaded. */
class RuleSetDownloadFailedException(cause: Throwable) : Exception(cause.message, cause)

internal fun CoreConfig.toStartOptions(autoSelector: AutoSelectorBuild? = null): StartOptions = StartOptions().apply {
    this@toStartOptions.xrayConfig?.let(app.qcoldthrone.mobile.bg.carrier.VpnListenerPolicy::checkXray)
    this@toStartOptions.xrayFullConfigs.forEach(app.qcoldthrone.mobile.bg.carrier.VpnListenerPolicy::checkXray)
    coreConfig = this@toStartOptions.coreConfig
    needXray = this@toStartOptions.needXray
    xrayConfig = this@toStartOptions.xrayConfig ?: ""
    xrayOutboundDNSStrategy = xrayDnsStrategy
    this@toStartOptions.xrayFullConfigs.forEach(::addXrayFullConfig)
    // mainwindow_profile_lifecycle.cpp:231-239: the idle window must outlast the probe interval, or the sidecar
    // restarts every round; full configs stay resident (0).
    if (autoSelector != null && (needXray || this@toStartOptions.xrayFullConfigs.isNotEmpty())) {
        xrayLazyStart = true
        xrayIdleSeconds = maxOf(120, autoSelector.intervalSec * 2)
        xrayFullIdleSeconds = 0
    }
}
