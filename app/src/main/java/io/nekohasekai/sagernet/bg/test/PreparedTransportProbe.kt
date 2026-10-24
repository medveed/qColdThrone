package app.qcoldthrone.mobile.bg.test

import kotlinx.coroutines.launch

/** Carrier startup has its own deadline; the URL timeout starts only in [measure].
 * Leases keep an already running VPN alive and are released on stop or a partial startup failure. */
internal suspend fun <P, L : AutoCloseable, R> withPreparedTransports(
    plans: Iterable<P>, acquire: suspend (P) -> L, measure: suspend () -> R,
): R {
    val leases=mutableListOf<L>()
    try {
        for(plan in plans) leases.add(acquire(plan))
        return measure()
    } finally {
        leases.asReversed().forEach { it.close() }
    }
}

/** Keep independent native preparations bounded, while a slow one does not block the others. */
internal suspend fun <P> runIndependentProbes(
    profiles:List<P>,concurrency:Int,cancelled:()->Boolean,
    sessionKey:(P)->Any?={ null },run:suspend (P)->Unit,
) = kotlinx.coroutines.coroutineScope {
    val gate=kotlinx.coroutines.sync.Semaphore(concurrency)
    val keys=profiles.associateWith(sessionKey)
    val sessions=keys.values.filterNotNull().distinct().associateWith { kotlinx.coroutines.sync.Mutex() }
    for(profile in profiles) launch {
        // Same-device variants queue before taking a global slot. They cannot evict
        // one another's remote session or prevent unrelated devices from starting.
        val mutex=keys[profile]?.let { sessions[it] }
        mutex?.lock()
        try {
            gate.acquire()
            try { if(!cancelled()) run(profile) }
            finally { gate.release() }
        } finally { mutex?.unlock() }
    }
}
