package app.opentv.launcher.apps

import org.junit.Assert.*
import org.junit.Test

class UtilityIconsTest {
    @Test fun verifiedVendorUtilitiesRetainNativeArtworkWithoutSystemFlags() {
        for (pkg in listOf("com.xiaomi.mitv.settings", "com.tcl.c2dm.client", "com.tcl.usercenter",
            "com.tcl.t_solo", "com.tcl.wholenetsearch", "com.tcl.walleve", "com.kookong.tvplus")) {
            assertTrue(pkg, UtilityIcons.isVerifiedUtility(pkg))
        }
    }
    @Test fun streamingBrandsAreNotClassifiedAsUtilities() {
        for (pkg in listOf("com.tcl.qiyiguo", "com.ktcp.csvideo", "com.xiaodianshi.tv.yst", "com.amazon.firetv.youtube")) {
            assertFalse(pkg, UtilityIcons.isVerifiedUtility(pkg))
        }
    }
    @Test fun unknownAppsKeepTheirExistingFallback() {
        assertFalse(UtilityIcons.isVerifiedUtility("org.example.player"))
    }
}
