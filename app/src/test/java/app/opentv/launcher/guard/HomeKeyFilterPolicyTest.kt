package app.opentv.launcher.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeKeyFilterPolicyTest {
    @Test
    fun android11AndOlderAlwaysFilterRegardlessOfPreference() {
        for (sdk in 21..30) {
            for (temporary in listOf(false, true)) {
                for (persistent in listOf(false, true)) {
                    assertTrue(HomeKeyFilterPolicy.shouldFilter(sdk, temporary, persistent))
                }
            }
        }
    }

    @Test
    fun android12AndNewerDefaultPreservesTemporaryFilteringOnly() {
        for (sdk in 31..36) {
            assertFalse(HomeKeyFilterPolicy.shouldFilter(sdk, temporary = false, persistent = false))
            assertTrue(HomeKeyFilterPolicy.shouldFilter(sdk, temporary = true, persistent = false))
        }
    }

    @Test
    fun persistentFilteringSurvivesWindowChangesAndOpenTVResume() {
        for (sdk in 31..36) {
            // The stock-launcher visit requests temporary filtering; other apps and OpenTV clear it.
            for (temporary in listOf(true, false, false)) {
                assertTrue(HomeKeyFilterPolicy.shouldFilter(sdk, temporary, persistent = true))
            }
        }
    }

    @Test
    fun disablingRestoresPolicyWithoutEndingStockLauncherVisit() {
        for (sdk in 31..36) {
            assertTrue(HomeKeyFilterPolicy.shouldFilter(sdk, temporary = false, persistent = true))
            assertFalse(HomeKeyFilterPolicy.shouldFilter(sdk, temporary = false, persistent = false))
            assertTrue(HomeKeyFilterPolicy.shouldFilter(sdk, temporary = true, persistent = true))
            assertTrue(HomeKeyFilterPolicy.shouldFilter(sdk, temporary = true, persistent = false))
            assertFalse(HomeKeyFilterPolicy.shouldFilter(sdk, temporary = false, persistent = false))
        }
    }
}
