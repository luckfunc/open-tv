package app.opentv.launcher.wallpaper

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** Stages beside the destination so rename publishes a complete file on the same filesystem. */
internal object AtomicImageStorage {
    private val photoPublication = Any()

    fun photo(directory: File, encode: (OutputStream) -> Boolean): File = stage(directory, encode) { staged ->
        synchronized(photoPublication) {
            var target: File
            do {
                target = File(directory, "${UUID.randomUUID()}.jpg")
            } while (target.exists())
            publish(staged, target)
        }
    }

    fun replace(target: File, encode: (OutputStream) -> Boolean): File =
        stage(target.parentFile!!, encode) { publish(it, target) }

    private fun stage(directory: File, encode: (OutputStream) -> Boolean, publish: (File) -> File): File {
        val staged = File.createTempFile(".image-", ".pending", directory)
        try {
            FileOutputStream(staged).use { output ->
                if (!encode(output)) throw IOException("Image encoding failed")
                output.fd.sync()
            }
            return publish(staged)
        } finally {
            staged.delete()
        }
    }

    private fun publish(staged: File, target: File): File {
        if (!staged.renameTo(target)) throw IOException("Image publication failed")
        return target
    }
}
