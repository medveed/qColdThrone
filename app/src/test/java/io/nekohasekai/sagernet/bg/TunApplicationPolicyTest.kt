package app.qcoldthrone.mobile.bg
import org.junit.Assert.*
import org.junit.Test
class TunApplicationPolicyTest {
    @Test fun nativeProbesStayOutsideRegularTunnel() {
        assertEquals(listOf("browser","qthrone"),TunApplicationPolicy.resolve("qthrone",emptyList(),listOf("browser")).excluded)
    }
    @Test fun includedAppsCannotCaptureNativeProbe() {
        val result=TunApplicationPolicy.resolve("qthrone",listOf("qthrone","browser","browser"),emptyList())
        assertEquals(listOf("browser"),result.included)
        assertTrue(result.excluded.isEmpty())
    }
    @Test(expected=IllegalArgumentException::class) fun appOnlySelectionCannotAccidentallyRouteEverything() {
        TunApplicationPolicy.resolve("qthrone",listOf("qthrone"),emptyList())
    }
}
