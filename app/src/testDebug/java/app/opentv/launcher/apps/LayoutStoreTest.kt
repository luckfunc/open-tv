package app.opentv.launcher.apps

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutStoreTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var context: Context
    private var writes = 0
    private val art by lazy { TileArtFactory.fromBanner(ColorDrawable(Color.BLUE)) }

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        prefs = app.getSharedPreferences("layout", Context.MODE_PRIVATE)
        context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                object : SharedPreferences by prefs {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = prefs.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun apply() { writes++; editor.apply() }
                            override fun commit(): Boolean { writes++; return editor.commit() }
                        }
                    }
                }
        }
    }

    @Test fun queryNeverInitializesOrWritesAndReconcileOnlyWritesForDiscoveries() {
        val store = LayoutStore(context)
        val all = listOf(app("z"), app("a"))
        repeat(4) { assertEquals(listOf("a", "z"), store.arrange(all).first.map { it.key }) }
        assertEquals(0, writes)
        assertFalse(prefs.contains("initialized"))
        store.reconcile(emptyList())
        assertEquals(0, writes)
        store.reconcile(all)
        assertEquals(1, writes)
        repeat(4) { store.reconcile(all); store.arrange(all) }
        assertEquals(1, writes)
        assertEquals(listOf("a", "z", "b"), store.arrange(all + app("b")).first.map { it.key })
        assertEquals(1, writes)
        store.reconcile(all + app("b"))
        assertEquals(2, writes)
        assertEquals("a,z,b", prefs.getString("order", null))
    }

    @Test fun previewAndCancelWriteNothingAndConfirmationWritesOnce() {
        val store = LayoutStore(context)
        val all = listOf(app("a"), app("b"), app("c"))
        store.reconcile(all)
        writes = 0
        val canceled = ReorderSession("a", store.arrange(all).first.map { it.key })
        canceled.moveTo(2)
        canceled.moveTo(1)
        assertEquals(listOf("a", "b", "c"), store.arrange(all).first.map { it.key })
        assertEquals(0, writes)
        val confirmed = ReorderSession("a", store.arrange(all).first.map { it.key })
        confirmed.moveTo(2)
        confirmed.moveTo(1)
        store.commitVisibleOrder(confirmed.keys)
        assertEquals(1, writes)
        repeat(3) { store.arrange(all) }
        assertEquals(1, writes)
        assertEquals(listOf("b", "a", "c"), LayoutStore(context).arrange(all).first.map { it.key })
    }

    @Test fun reconciliationAndReorderPreserveHiddenAndTemporarilyAbsentSlots() {
        prefs.edit().putString("order", "a,hidden,b,missing,c")
            .putStringSet("hidden", setOf("hidden", "absent_hidden"))
            .putBoolean("initialized", true).commit()
        val store = LayoutStore(context)
        val all = listOf(app("a"), app("b"), app("c"), app("hidden"))
        store.reconcile(all)
        assertEquals(0, writes)
        store.commitVisibleOrder(listOf("c", "a", "b"))
        assertEquals(1, writes)
        assertEquals("c,hidden,a,missing,b", prefs.getString("order", null))
        store.reconcile(all + app("missing") + app("absent_hidden") + app("new"))
        assertEquals(2, writes)
        val (visible, hidden) = LayoutStore(context).arrange(all + app("missing") + app("absent_hidden") + app("new"))
        assertEquals(listOf("c", "a", "missing", "b", "new"), visible.map { it.key })
        assertEquals(listOf("hidden", "absent_hidden"), hidden.map { it.key })
    }

    @Test fun firstRunDefaultsAreReadOnlyUntilReconciledAndSurviveRecreation() {
        val all = listOf(app("com.android.tv.settings"), app("com.xiaomi.mitv.settings"))
        val store = LayoutStore(context)
        assertEquals(listOf("com.android.tv.settings"), store.arrange(all).second.map { it.key })
        assertEquals(0, writes)
        store.reconcile(all)
        assertEquals(1, writes)
        assertEquals(listOf("com.android.tv.settings"), LayoutStore(context).arrange(all).second.map { it.key })
    }

    @Test fun invalidReorderDoesNotWriteOrChangeTheStoredOrder() {
        val store = LayoutStore(context)
        store.reconcile(listOf(app("a"), app("b"), app("hidden")))
        store.hide("hidden")
        writes = 0
        val before = prefs.getString("order", null)
        for (keys in listOf(listOf("a", "a"), listOf("unknown"), listOf("hidden", "a"))) {
            assertThrows(IllegalArgumentException::class.java) { store.commitVisibleOrder(keys) }
        }
        assertEquals(0, writes)
        assertEquals(before, prefs.getString("order", null))
    }

    private fun app(key: String) = AppEntry(key, key, ComponentName(key, "Main"), false, true, art)
}
