package app.opentv.launcher.apps

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import app.opentv.launcher.wallpaper.PhoneServer
import app.opentv.launcher.wallpaper.Wallpapers
import app.opentv.launcher.settings.OpenTvSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowApplicationPackageManager
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AppCatalogIconTest.CatalogPackageManager::class], instrumentedPackages = ["app.opentv.launcher.apps", "app.opentv.launcher.wallpaper", "app.opentv.launcher.settings"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppCatalogIconTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var pm: CatalogPackageManager
    private val a = "app.opentv.synthetic_a"
    private val b = "app.opentv.synthetic_b"

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        val files = temporary.newFolder()
        val cache = temporary.newFolder()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
        CustomIcons.init(context)
        pm = Shadow.extract(context.packageManager)
        pm.activities = emptyList()
        runBlocking { AppCatalog.load(context) }
        pm.activities = listOf(activity(a), activity(b))
    }

    @Test fun uploadResetRoundtripPublishesFreshArtAndRetainsUnrelatedCacheAndFiles() = runBlocking {
        val before = AppCatalog.load(context).associateBy { it.key }
        val legacy = File(context.filesDir, "icons/app.opentv.legacy_absent.png").apply { writeBytes(image(Color.BLUE)) }
        val legacyBytes = legacy.readBytes()
        val default = TileArtFactory.flatten(before.getValue(a).art)
        val queries = pm.queries
        val stamp = CustomIcons.stamp(a)
        assertEquals(IconMutationResult.Applied, AppCatalog.saveIcon(context, a, image(Color.RED).inputStream()))
        assertTrue(CustomIcons.has(a))
        assertTrue(CustomIcons.stamp(a) > stamp)
        assertSame(before.getValue(b).art, AppCatalog.entries.value.first { it.key == b }.art)
        val custom = TileArtFactory.flatten(AppCatalog.loaded.first { it.key == a }.art)
        assertEquals(Color.RED, custom.getPixel(custom.width / 2, custom.height / 2))
        custom.recycle()
        val uploadedStamp = CustomIcons.stamp(a)
        assertEquals(IconMutationResult.Applied, AppCatalog.resetIcon(context, a))
        assertTrue(CustomIcons.stamp(a) > uploadedStamp)
        assertFalse(CustomIcons.has(a))
        val reset = TileArtFactory.flatten(AppCatalog.loaded.first { it.key == a }.art)
        assertTrue(default.sameAs(reset))
        assertSame(before.getValue(b).art, AppCatalog.loaded.first { it.key == b }.art)
        assertEquals(queries, pm.queries)
        assertArrayEquals(legacyBytes, legacy.readBytes())
        default.recycle()
        reset.recycle()
    }

    @Test fun customArtworkOverridesNativeUtilityAndResetRestoresExactNativePixels() = runBlocking {
        val utility = "com.xiaomi.mitv.settings"
        pm.activities = listOf(activity(utility))
        val original = TileArtFactory.flatten(AppCatalog.load(context).single().art)
        val native = TileArtFactory.flatten(TileArtFactory.fromNativeIcon(ColorDrawable(Color.GREEN)))
        assertTrue(original.sameAs(native))
        assertEquals(Color.GREEN, original.getPixel(original.width / 2, original.height / 2))
        assertEquals(Color.GREEN, original.getPixel(0, original.height / 2))
        assertOpaque(original)
        assertEquals(IconMutationResult.Applied, AppCatalog.saveIcon(context, utility, image(Color.RED).inputStream()))
        val custom = TileArtFactory.flatten(AppCatalog.loaded.single().art)
        assertEquals(Color.RED, custom.getPixel(custom.width / 2, custom.height / 2))
        assertEquals(IconMutationResult.Applied, AppCatalog.resetIcon(context, utility))
        val reset = TileArtFactory.flatten(AppCatalog.loaded.single().art)
        assertTrue(original.sameAs(reset))
        listOf(original, native, custom, reset).forEach { it.recycle() }
    }

    @Test fun systemAndUpdatedSystemIconsKeepCompleteColoredLayers() = runBlocking {
        for (flag in listOf(ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) {
            pm.activities = emptyList()
            AppCatalog.load(context)
            val icon = android.graphics.drawable.LayerDrawable(arrayOf(
                ColorDrawable(Color.BLUE),
                ColorDrawable(Color.GREEN),
            )).apply { setLayerInset(1, 12, 12, 12, 12) }
            val info = activity(a, nativeIcon = icon).apply { applicationInfo.flags = flag }
            pm.activities = listOf(info)
            val bitmap = TileArtFactory.flatten(AppCatalog.load(context).single().art)
            val expected = TileArtFactory.flatten(TileArtFactory.fromNativeIcon(icon))
            assertTrue(bitmap.sameAs(expected))
            assertEquals(Color.GREEN, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            assertEquals(Color.BLUE, bitmap.getPixel((bitmap.width - bitmap.height) / 2 + 2, bitmap.height / 2))
            assertEquals(Color.BLUE, bitmap.getPixel(0, bitmap.height / 2))
            assertOpaque(bitmap)
            bitmap.recycle()
            expected.recycle()
        }
    }

    @Test fun preinstalledStreamingRecipesStayUnchangedAndCustomArtworkStillWins() = runBlocking {
        for (pkg in listOf("com.zmg.ott", "com.lizongying.mytv", "com.sohuott.tv.vod")) {
            pm.activities = emptyList()
            AppCatalog.load(context)
            pm.activities = listOf(activity(pkg).apply { applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM })
            val recipe = requireNotNull(TileRecipes.of(pkg))
            val expectedArt = if (recipe.glyph != null) {
                TileArtFactory.glyphTile(recipe.top ?: 0, recipe.bottom ?: 0, recipe.glyph)
            } else TileArtFactory.fromIcon(ColorDrawable(Color.GREEN), recipe)
            val expected = TileArtFactory.flatten(expectedArt)
            val original = TileArtFactory.flatten(AppCatalog.load(context).single().art)
            assertTrue(pkg, original.sameAs(expected))
            assertEquals(IconMutationResult.Applied, AppCatalog.saveIcon(context, pkg, image(Color.RED).inputStream()))
            val custom = TileArtFactory.flatten(AppCatalog.loaded.single().art)
            assertEquals(Color.RED, custom.getPixel(custom.width / 2, custom.height / 2))
            assertEquals(IconMutationResult.Applied, AppCatalog.resetIcon(context, pkg))
            val reset = TileArtFactory.flatten(AppCatalog.loaded.single().art)
            assertTrue(pkg, original.sameAs(reset))
            listOf(expected, original, custom, reset).forEach { it.recycle() }
        }
    }

    @Test fun previewWaitsForCompletedPublicationInsteadOfCachingOldArtWithANewStamp() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blockLabel = false
        pm.activities = listOf(object : ActivityInfo() {
            override fun loadLabel(pm: PackageManager): CharSequence {
                if (blockLabel) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return "Synthetic"
            }
            override fun loadIcon(pm: PackageManager): Drawable = ColorDrawable(Color.GREEN)
        }.apply {
            packageName = a
            name = "$a.Main"
            applicationInfo = ApplicationInfo().apply { packageName = a; enabled = true }
        })
        AppCatalog.load(context)
        blockLabel = true
        val save = async(Dispatchers.Default) { AppCatalog.saveIcon(context, a, image(Color.RED).inputStream()) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(CustomIcons.has(a))
            val preview = async(start = CoroutineStart.UNDISPATCHED) { AppCatalog.iconEntry(a) }
            assertFalse(preview.isCompleted)
            release.countDown()
            assertEquals(IconMutationResult.Applied, save.await())
            val bitmap = TileArtFactory.flatten(requireNotNull(preview.await()).art)
            assertEquals(Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            bitmap.recycle()
        } finally {
            release.countDown()
        }
    }

    @Test fun invalidKeysUnknownAppsAndFailedDeletionAreExplicitAndPublishNothing() = runBlocking {
        AppCatalog.load(context)
        val before = AppCatalog.entries.value
        val queries = pm.queries
        for (key in listOf("../escape", "input:hdmi", "", "$a/../other")) {
            assertEquals(IconMutationResult.InvalidKey, AppCatalog.resetIcon(context, key))
            assertEquals(IconMutationResult.InvalidKey, AppCatalog.saveIcon(context, key, image(Color.RED).inputStream()))
            assertFalse(CustomIcons.clear(key))
        }
        assertEquals(IconMutationResult.UnknownApp, AppCatalog.resetIcon(context, "app.opentv.absent"))
        assertEquals(IconMutationResult.UnknownApp, AppCatalog.saveIcon(context, "app.opentv.absent", image(Color.RED).inputStream()))
        val blocked = File(context.filesDir, "icons/$a.png").apply { mkdir() }
        val child = File(blocked, "synthetic-existing").apply { writeText("preserve") }
        assertEquals(IconMutationResult.StorageFailure, AppCatalog.resetIcon(context, a))
        assertEquals(IconMutationResult.StorageFailure, AppCatalog.saveIcon(context, a, image(Color.RED).inputStream()))
        assertEquals("preserve", child.readText())
        assertSame(before, AppCatalog.entries.value)
        assertEquals(queries, pm.queries)
    }

    @Test fun corruptUploadPreservesPreviousIconAndSnapshot() = runBlocking {
        AppCatalog.load(context)
        assertEquals(IconMutationResult.Applied, AppCatalog.saveIcon(context, a, image(Color.RED).inputStream()))
        val file = File(context.filesDir, "icons/$a.png")
        val bytes = file.readBytes()
        val stamp = CustomIcons.stamp(a)
        val snapshot = AppCatalog.entries.value
        assertEquals(IconMutationResult.StorageFailure, AppCatalog.saveIcon(context, a, "corrupt".byteInputStream()))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(stamp, CustomIcons.stamp(a))
        assertSame(snapshot, AppCatalog.entries.value)
    }

    @Test fun artworkFailureKeepsTheLaunchableAppAndLogsTheFallback() = runBlocking {
        pm.activities = listOf(activity(a, broken = true))
        val app = AppCatalog.load(context).single()
        assertEquals(a, app.key)
        assertEquals(a, app.launchIntent().component!!.packageName)
        assertTrue(app.launchIntent().hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals("Synthetic $a", app.label)
        val bitmap = TileArtFactory.flatten(app.art)
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        bitmap.recycle()
        assertTrue(ShadowLog.getLogsForTag("OpenTVApps").any { it.msg.contains("Artwork unavailable for $a") && it.throwable != null })
        assertEquals(IconMutationResult.Applied, AppCatalog.saveIcon(context, a, image(Color.RED).inputStream()))
        val custom = TileArtFactory.flatten(AppCatalog.loaded.single().art)
        assertEquals(Color.RED, custom.getPixel(custom.width / 2, custom.height / 2))
        custom.recycle()
        assertEquals(IconMutationResult.Applied, AppCatalog.resetIcon(context, a))
        assertEquals(a, AppCatalog.loaded.single().key)
    }

    @Test fun discoveryPrunesAbsentArtworkButRetainsUnrelatedArtworkAndCustomFiles() = runBlocking {
        val before = AppCatalog.load(context).associateBy { it.key }
        val legacy = File(context.filesDir, "icons/app.opentv.synthetic_legacy.png").apply { writeBytes(image(Color.RED)) }
        val bytes = legacy.readBytes()
        pm.activities = listOf(activity(b))
        assertEquals(listOf(b), AppCatalog.load(context).map { it.key })
        assertSame(before.getValue(b).art, AppCatalog.loaded.single().art)
        assertArrayEquals(bytes, legacy.readBytes())
        pm.activities = listOf(activity(a), activity(b))
        val returned = AppCatalog.load(context).associateBy { it.key }
        assertNotSame(before.getValue(a).art, returned.getValue(a).art)
        assertSame(before.getValue(b).art, returned.getValue(b).art)
    }

    @Test fun concurrentMutationsAndDiscoveryPublishCumulativeFreshArtwork() = runBlocking {
        AppCatalog.load(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bytes = image(Color.RED)
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return super.read(buffer, offset, length)
            }
        }
        val first = async(Dispatchers.Default) { AppCatalog.saveIcon(context, a, input) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) { AppCatalog.saveIcon(context, b, image(Color.BLUE).inputStream()) }
            val discovery = async(Dispatchers.Default) { AppCatalog.load(context) }
            release.countDown()
            assertEquals(IconMutationResult.Applied, first.await())
            assertEquals(IconMutationResult.Applied, second.await())
            discovery.await()
            val latest = AppCatalog.entries.value.associateBy { it.key }
            for ((key, color) in listOf(a to Color.RED, b to Color.BLUE)) {
                val bitmap = TileArtFactory.flatten(latest.getValue(key).art)
                assertEquals(color, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                bitmap.recycle()
            }
            assertEquals(IconMutationResult.Applied, AppCatalog.resetIcon(context, a))
            assertTrue(CustomIcons.has(b))
            val retained = AppCatalog.loaded.first { it.key == b }.art
            AppCatalog.load(context)
            assertSame(retained, AppCatalog.loaded.first { it.key == b }.art)
        } finally {
            release.countDown()
            input.close()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun phoneUploadPreviewAndResetUseTheSameCompletedCatalogMutation() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ReflectionHelpers.getField<CoroutineScope>(Wallpapers, "scope").cancel()
        val wallpaperPrefs = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
        wallpaperPrefs.edit().putString("screensaver", "clock").putString("selected", "grad:aurora").commit()
        val settingsPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settingsPrefs.edit().putString("focus_style", "high_contrast").putBoolean("show_all_names", true).commit()
        val weatherPrefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
        weatherPrefs.edit().putString("source", "manual").putString("place", "legacy-city").commit()
        val retainedPhoto = File(context.filesDir, "photos/retained.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(image(Color.BLUE))
        }
        val retainedBytes = retainedPhoto.readBytes()
        val dailyDir = File(context.filesDir, "daily").apply { mkdirs() }
        File(dailyDir, "20260101.jpg").writeBytes(image(Color.GREEN))
        val dailyIndex = File(dailyDir, "index.json").apply {
            writeText("""[{"date":"20260101","title":"Legacy Bing photo","caption":"Retained attribution"}]""")
        }
        val retainedIndex = dailyIndex.readBytes()
        val retainedPrefs = wallpaperPrefs.all.toMap()
        Wallpapers.init(context)
        OpenTvSettings.init(context)
        assertEquals(retainedPrefs, wallpaperPrefs.all)
        assertEquals("Retained attribution", Wallpapers.items.first { it.id == "daily:20260101" }.caption)
        assertArrayEquals(retainedIndex, dailyIndex.readBytes())
        assertEquals("grad:aurora", Wallpapers.selectedId)
        assertEquals("clock", wallpaperPrefs.getString("screensaver", null))
        assertEquals("high_contrast", settingsPrefs.getString("focus_style", null))
        assertTrue(OpenTvSettings.showAllNames)
        assertEquals("legacy-city", weatherPrefs.getString("place", null))
        assertArrayEquals(retainedBytes, retainedPhoto.readBytes())
        AppCatalog.load(context)
        val server = PhoneServer(context, "synthetic-icons", { AutoCloseable {} }, port = 0)
        assertTrue(server.open())
        try {
            val state = JSONObject(String(request(server, "/api/state").second))
            assertFalse(state.has("screensaver"))
            assertFalse(state.has("weather"))
            assertFalse(state.has("focusStyle"))
            val html = String(request(server, "/").second)
            assertTrue(html.contains("TV personalization"))
            assertTrue(html.contains("OpenTV"))
            val unauthenticated = URL("http://127.0.0.1:${server.listeningPort}/wrong-token/api/weather/city").openConnection() as HttpURLConnection
            try {
                unauthenticated.requestMethod = "POST"
                unauthenticated.doOutput = true
                unauthenticated.outputStream.use { it.write("name=changed".toByteArray()) }
                assertEquals(404, unauthenticated.responseCode)
                assertEquals("close", unauthenticated.getHeaderField("Connection"))
                assertEquals("legacy-city", weatherPrefs.getString("place", null))
            } finally {
                unauthenticated.disconnect()
            }
            val zhConfig = android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
            }
            val zhServer = PhoneServer(context.createConfigurationContext(zhConfig), "synthetic-icons", { AutoCloseable {} }, port = 0)
            assertTrue(zhServer.open())
            try {
                assertTrue(String(request(zhServer, "/").second).contains("<p>电视个性化</p>"))
            } finally {
                zhServer.close()
            }
            listOf("{{DEVICE}}", "{{SUBTITLE}}", "data-mode=", "screensaver", "ssHint", "屏保", "屏幕保护", "航拍", "focusStyle", "focusHint", "weather", "cityForm").forEach {
                assertFalse(it, html.contains(it))
            }
            for (retired in listOf("/api/focus", "/api/weather/city", "/api/weather/auto")) {
                assertEquals(404, request(server, retired, "name=unused".toByteArray()).first)
                assertEquals(200, request(server, "/api/state").first)
            }
            val beforeRetiredRequests = Wallpapers.items
            assertEquals(404, request(server, "/api/screensaver").first)
            for (mode in listOf("photos", "scenic", "clock", "unknown", "")) {
                assertEquals(404, request(server, "/api/screensaver", "mode=$mode".toByteArray()).first)
                val unchanged = JSONObject(String(request(server, "/api/state").second))
                assertFalse(unchanged.has("screensaver"))
                assertEquals(state.toString(), unchanged.toString())
                assertEquals(retainedPrefs, wallpaperPrefs.all)
                assertEquals(beforeRetiredRequests, Wallpapers.items)
                assertArrayEquals(retainedBytes, retainedPhoto.readBytes())
                assertArrayEquals(retainedIndex, dailyIndex.readBytes())
            }
            val photoBoundary = "synthetic-photo-boundary"
            val photoBody = ByteArrayOutputStream().apply {
                write(("--$photoBoundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"synthetic.png\"\r\n" +
                    "Content-Type: image/png\r\n\r\n").toByteArray())
                write(image(Color.BLUE))
                write("\r\n--$photoBoundary--\r\n".toByteArray())
            }.toByteArray()
            val photo = request(server, "/api/upload", photoBody, "multipart/form-data; boundary=$photoBoundary")
            assertEquals(200, photo.first)
            assertEquals(1, JSONObject(String(photo.second)).getInt("added"))
            assertFalse(JSONObject(String(photo.second)).has("screensaver"))
            assertEquals("clock", wallpaperPrefs.getString("screensaver", null))
            assertArrayEquals(retainedBytes, retainedPhoto.readBytes())
            assertEquals(200, request(server, "/api/wallpaper", "id=grad:aurora".toByteArray()).first)
            assertEquals("grad:aurora", Wallpapers.selectedId)
            val default = request(server, "/tile/$a").second
            val boundary = "synthetic-boundary"
            val body = ByteArrayOutputStream().apply {
                write(("--$boundary\r\nContent-Disposition: form-data; name=\"app\"\r\n\r\n$a\r\n" +
                    "--$boundary\r\nContent-Disposition: form-data; name=\"picture\"; filename=\"synthetic.png\"\r\n" +
                    "Content-Type: image/png\r\n\r\n").toByteArray())
                write(image(Color.RED))
                write("\r\n--$boundary--\r\n".toByteArray())
            }.toByteArray()
            val uploaded = request(server, "/api/icon", body, "multipart/form-data; boundary=$boundary")
            assertEquals(200, uploaded.first)
            assertTrue(JSONObject(String(uploaded.second)).getBoolean("saved"))
            assertTrue(CustomIcons.has(a))
            assertFalse(default.contentEquals(request(server, "/tile/$a").second))
            val homeArt = TileArtFactory.flatten(AppCatalog.entries.value.first { it.key == a }.art)
            assertEquals(Color.RED, homeArt.getPixel(homeArt.width / 2, homeArt.height / 2))
            homeArt.recycle()
            assertEquals(200, request(server, "/api/icon/reset", "app=$a".toByteArray()).first)
            assertFalse(CustomIcons.has(a))
            assertArrayEquals(default, request(server, "/tile/$a").second)
            assertEquals(400, request(server, "/api/icon/reset", "app=../invalid".toByteArray()).first)
            val blocked = File(context.filesDir, "icons/$a.png").apply { mkdir() }
            File(blocked, "synthetic-existing").writeText("preserve")
            assertEquals(500, request(server, "/api/icon/reset", "app=$a".toByteArray()).first)
        } finally {
            server.close()
            Dispatchers.resetMain()
        }
    }

    private fun request(server: PhoneServer, path: String, body: ByteArray? = null, type: String = "application/x-www-form-urlencoded"): Pair<Int, ByteArray> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}/synthetic-icons$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 10000
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", type)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (body != null) assertEquals("close", connection.getHeaderField("Connection"))
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return status to stream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    @Test fun nativeAlphaPaddingDoesNotChangeVisualSizeOrOriginalColors() {
        fun render(padding: Int): Bitmap {
            val source = Bitmap.createBitmap(80 + padding * 2, 80 + padding * 2, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(source).apply {
                drawRect(padding.toFloat(), padding.toFloat(), (padding + 80).toFloat(), (padding + 80).toFloat(),
                    android.graphics.Paint().apply { color = Color.BLUE })
                drawCircle((padding + 40).toFloat(), (padding + 40).toFloat(), 24f,
                    android.graphics.Paint().apply { color = Color.RED })
            }
            val icon = android.graphics.drawable.BitmapDrawable(context.resources, source).apply {
                setTargetDensity(source.density)
                setBounds(3, 4, 27, 29)
            }
            return try {
                TileArtFactory.flatten(TileArtFactory.fromNativeIcon(icon)).also {
                    assertEquals(android.graphics.Rect(3, 4, 27, 29), icon.bounds)
                }
            } finally { source.recycle() }
        }
        val first = render(10)
        val second = render(40)
        try {
            assertTrue(first.sameAs(second))
            assertOpaque(first)
            assertEquals(Color.RED, first.getPixel(first.width / 2, first.height / 2))
            assertEquals(Color.BLUE, first.getPixel(0, 0))
            assertEquals(Color.BLUE, first.getPixel(first.width - 1, first.height - 1))
            assertEquals(first.getPixel(0, 0), first.getPixel(first.width / 2, (first.height * 0.15f).toInt()))
        } finally { first.recycle(); second.recycle() }
    }

    @Test fun nativeWideAndTallArtworkIsCenteredContainedAndNotCropped() {
        for ((width, height) in listOf(160 to 40, 40 to 160, 80 to 80)) {
            val source = Bitmap.createBitmap(width + 20, height + 20, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(source)
            val paint = android.graphics.Paint().apply { color = Color.RED }
            canvas.drawRect(10f, 10f, (width + 10).toFloat(), (height + 10).toFloat(), paint)
            paint.color = Color.BLUE
            canvas.drawRect(10f, 10f, (width / 2 + 10).toFloat(), (height + 10).toFloat(), paint)
            val icon = android.graphics.drawable.BitmapDrawable(context.resources, source).apply { setTargetDensity(source.density) }
            val result = TileArtFactory.flatten(TileArtFactory.fromNativeIcon(icon))
            try {
                assertOpaque(result)
                val plate = result.getPixel(0, 0)
                val xs = mutableListOf<Int>(); val ys = mutableListOf<Int>()
                for (y in 0 until result.height) for (x in 0 until result.width) {
                    if (result.getPixel(x, y) != plate) { xs.add(x); ys.add(y) }
                }
                val left = xs.min(); val right = xs.max(); val top = ys.min(); val bottom = ys.max()
                assertTrue(kotlin.math.abs(result.width - 1 - left - right) <= 1)
                assertTrue(kotlin.math.abs(result.height - 1 - top - bottom) <= 1)
                val drawnWidth = right - left + 1; val drawnHeight = bottom - top + 1
                assertEquals(width.toDouble() / height, drawnWidth.toDouble() / drawnHeight, 0.08)
                assertTrue(drawnWidth <= result.width * 0.72f + 2)
                assertTrue(drawnHeight <= result.height * 0.64f + 2)
                assertTrue(top >= result.height * 0.17f)
                assertEquals(Color.BLUE, result.getPixel(left + drawnWidth / 4, result.height / 2))
                assertEquals(Color.RED, result.getPixel(right - drawnWidth / 4, result.height / 2))
            } finally { source.recycle(); result.recycle() }
        }
    }

    @Test fun existingNativeBannersKeepTheirOriginalArtwork() = runBlocking {
        for (pkg in listOf("com.xiaomi.mitv.settings", a)) {
            pm.activities = emptyList()
            AppCatalog.load(context)
            pm.activities = listOf(object : ActivityInfo() {
                override fun loadLabel(pm: PackageManager): CharSequence = "Synthetic"
                override fun loadIcon(pm: PackageManager): Drawable = ColorDrawable(Color.GREEN)
                override fun loadBanner(pm: PackageManager): Drawable = ColorDrawable(Color.MAGENTA)
            }.apply {
                packageName = pkg; name = "$pkg.Main"
                applicationInfo = ApplicationInfo().apply { packageName = pkg; enabled = true; flags = ApplicationInfo.FLAG_SYSTEM }
            })
            val result = TileArtFactory.flatten(AppCatalog.load(context).single().art)
            try {
                assertEquals(Color.MAGENTA, result.getPixel(0, 0))
            } finally { result.recycle() }
        }
    }

    @Test fun nativeWhitePlateAndAdaptiveCompositeKeepTheirOriginalColors() {
        val foreground = android.graphics.drawable.LayerDrawable(arrayOf(ColorDrawable(Color.TRANSPARENT), ColorDrawable(Color.RED)))
            .apply { setLayerInset(1, 60, 60, 60, 60) }
        val adaptive = android.graphics.drawable.AdaptiveIconDrawable(ColorDrawable(Color.BLUE), foreground)
        val adaptiveResult = TileArtFactory.flatten(TileArtFactory.fromNativeIcon(adaptive))
        val whitePlate = android.graphics.drawable.LayerDrawable(arrayOf(ColorDrawable(Color.WHITE), ColorDrawable(Color.RED)))
            .apply { setLayerInset(1, 60, 60, 60, 60) }
        val whiteResult = TileArtFactory.flatten(TileArtFactory.fromNativeIcon(whitePlate))
        try {
            assertOpaque(adaptiveResult)
            assertOpaque(whiteResult)
            assertEquals(Color.RED, adaptiveResult.getPixel(adaptiveResult.width / 2, adaptiveResult.height / 2))
            assertEquals(Color.RED, whiteResult.getPixel(whiteResult.width / 2, whiteResult.height / 2))
            assertEquals(Color.WHITE, whiteResult.getPixel(0, 0))
            val pixels = IntArray(adaptiveResult.width * adaptiveResult.height)
            adaptiveResult.getPixels(pixels, 0, adaptiveResult.width, 0, 0, adaptiveResult.width, adaptiveResult.height)
            assertTrue(pixels.any { it == Color.BLUE })
        } finally { adaptiveResult.recycle(); whiteResult.recycle() }
    }

    private fun assertOpaque(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.all { Color.alpha(it) == 255 })
    }

    private fun activity(key: String, broken: Boolean = false, nativeIcon: Drawable? = null): ActivityInfo = object : ActivityInfo() {
        override fun loadLabel(pm: PackageManager): CharSequence = "Synthetic $key"
        override fun loadIcon(pm: PackageManager): Drawable {
            if (broken) throw IllegalStateException("synthetic artwork failure")
            return nativeIcon ?: ColorDrawable(Color.GREEN)
        }
    }.apply {
        packageName = key
        name = "$key.Main"
        applicationInfo = ApplicationInfo().apply { packageName = key; enabled = true }
        exported = true
        enabled = true
    }

    private fun image(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Implements(className = "android.app.ApplicationPackageManager")
    class CatalogPackageManager : ShadowApplicationPackageManager() {
        var activities: List<ActivityInfo> = emptyList()
        @Volatile var queries = 0
        @Implementation override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<ResolveInfo> {
            queries++
            return if (intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                activities.map { info -> ResolveInfo().apply { activityInfo = info } }.toMutableList()
            } else mutableListOf()
        }
    }
}
