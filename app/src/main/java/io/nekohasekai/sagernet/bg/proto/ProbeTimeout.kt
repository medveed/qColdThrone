package app.qcoldthrone.mobile.bg.proto

/** A native SOCKS bridge is already listening before its first tunnel DNS/TCP round trip completes. */
internal object ProbeTimeout {
    fun effective(requestedMs: Int, nativeTransport: Boolean): Int =
        if(nativeTransport) requestedMs.coerceAtLeast(15_000) else requestedMs
}
