package app.qthrone.mobile.group

import app.qthrone.mobile.database.*
import app.qthrone.mobile.route.ProviderRouting
import app.qthrone.mobile.route.ProviderRouteProfiles
import app.qthrone.mobile.outbound.json.*

object ProviderRouteStore {
    fun apply(gid: Long, commands: List<ProviderRouting.Command>, fullJson: Boolean = false) {
        if (commands.isEmpty()) return
        if (GroupRepo.get(gid)?.subOptions?.providerIgnoreImport == true) return
        // A failed geo download shows on the status card and is retried on connect; the routing is saved regardless.
        for (command in commands) command.document?.let {
            runCatching { app.qthrone.mobile.route.ProviderGeoAssets.prepare(gid,it) }
                .onFailure { error -> app.qthrone.mobile.ktx.Logs.w("Provider geo files", error) }
        }
        SagerDatabase.instance.runInTransaction {
            val group = GroupRepo.get(gid) ?: return@runInTransaction
            val options = group.subOptions
            if (options.providerIgnoreImport) return@runInTransaction
            val profiles = (JsonInput.parseValue(options.providerRoutingProfiles) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            for (command in commands) {
                val document = command.document ?: continue
                ProviderRouting.profile(document, command.provider)
            }
            val merged=ProviderRouteProfiles.merge(profiles,options.providerRouting,options.routingProvider,commands,options.providerRoutingManual)
            options.providerRouting=merged.selected;options.routingProvider=merged.provider
            options.providerRoutingProfiles = JsonArray().apply { merged.profiles.forEach { add(it) } }.toCompact()
            SagerDatabase.groupDao.update(group)
        }
    }
}
