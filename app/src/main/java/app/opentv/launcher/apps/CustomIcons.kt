package app.opentv.launcher.apps

import android.content.Context
import android.graphics.Bitmap
import app.opentv.launcher.wallpaper.ImageImport
import app.opentv.launcher.wallpaper.Images
import java.io.File
import java.io.InputStream

/**
 * Pictures the owner chose for app tiles, sent from the phone page. Each is cropped to the tile's
 * 5:3 and kept by package name in files/icons; a tile with one draws it instead of the app's own
 * artwork (AppCatalog). Only the owner's own uploads live here, and removing one only ever deletes
 * that app's file.
 */
object CustomIcons {
    /** 1280×768: sharp 5:3 artwork. Existing 16:9 files remain readable and are not rewritten. */
    private const val W = 1280
    private const val H = 768

    private lateinit var dir: File
    private val revisions = HashMap<String, Long>()

    @Synchronized fun init(context: Context) {
        val directory = File(context.filesDir, "icons")
        if (!::dir.isInitialized || dir != directory) revisions.clear()
        dir = directory.apply { mkdirs() }
    }

    private fun fileFor(pkg: String) = File(dir, "$pkg.png")

    fun has(pkg: String): Boolean = ::dir.isInitialized && isValidKey(pkg) && fileFor(pkg).isFile

    /** Changes even for reset or two uploads in the same filesystem clock tick. */
    @Synchronized fun stamp(pkg: String): Long =
        revisions[pkg] ?: if (::dir.isInitialized && isValidKey(pkg)) fileFor(pkg).lastModified() else 0L

    /** The app's picture at tile size, or null. Off the main thread. */
    fun load(pkg: String): Bitmap? = if (has(pkg)) Images.decodeCover(fileFor(pkg), W, H) else null

    /** Off the main thread. A failed import leaves the previous picture unchanged. */
    internal fun save(pkg: String, input: InputStream, cacheDir: File): Boolean {
        if (!::dir.isInitialized || !isValidKey(pkg)) return false
        if (!ImageImport.icon(input, cacheDir, fileFor(pkg), W, H)) return false
        changed(pkg)
        return true
    }

    internal fun clear(pkg: String): Boolean {
        if (!::dir.isInitialized || !isValidKey(pkg)) return false
        val file = fileFor(pkg)
        if (file.exists() && (!file.isFile || !file.delete())) return false
        changed(pkg)
        return true
    }

    @Synchronized private fun changed(pkg: String) {
        revisions[pkg] = maxOf(stamp(pkg) + 1, System.currentTimeMillis())
    }

    /** Package syntax also keeps [pkg] a plain file name. */
    fun isValidKey(pkg: String): Boolean = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$").matches(pkg)
}
