package app.qcoldthrone.mobile.outbound.config

import app.qcoldthrone.mobile.outbound.Outbound
import app.qcoldthrone.mobile.outbound.types.Chain
import app.qcoldthrone.mobile.outbound.types.Custom
import app.qcoldthrone.mobile.outbound.types.Hysteria
import app.qcoldthrone.mobile.outbound.types.OffsiteConfig
import app.qcoldthrone.mobile.outbound.types.Socks

/** The desktop's Profile::Chain() / Custom() / Hysteria() / Socks() casts, which yield nothing for any other type. */
internal object TypeAccess {
    /** chain.h:10 `list`, stored in -> out; empty for anything that is not a Chain. */
    fun chainHops(outbound: Outbound): List<Long> = (outbound as? Chain)?.list ?: emptyList()

    fun asCustom(outbound: Outbound): Custom? = outbound as? Custom

    /** The "Offsite JSON Config" profile (docs/offsite-json-config.md §6, §7). */
    fun asOffsiteConfig(outbound: Outbound): OffsiteConfig? = outbound as? OffsiteConfig

    fun asSocks(outbound: Outbound): Socks? = outbound as? Socks

    /** isCustomFullConfig (generate.cpp:419-422). */
    fun isCustomFullConfig(outbound: Outbound): Boolean = (outbound as? Custom)?.isFullConfig() == true

    /** hysteria.h:57-59 RealmActive() (generate.cpp:1357). */
    fun realmActive(outbound: Outbound): Boolean = (outbound as? Hysteria)?.realmActive() == true
}
