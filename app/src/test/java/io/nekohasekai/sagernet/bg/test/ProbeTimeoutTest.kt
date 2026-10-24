package app.qcoldthrone.mobile.bg.test

import app.qcoldthrone.mobile.bg.proto.ProbeTimeout
import org.junit.Assert.*
import org.junit.Test

class ProbeTimeoutTest {
    @Test fun nativeFirstDnsHandshakeGetsBoundedAllowance() {
        assertEquals(15000,ProbeTimeout.effective(3000,true))
        assertEquals(30000,ProbeTimeout.effective(30000,true))
        assertEquals(3000,ProbeTimeout.effective(3000,false))
    }
}
