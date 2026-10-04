package pl.hamlogbridge.upload

import android.content.Context
import android.os.Environment
import pl.hamlogbridge.adif.Adif
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Appends every logged QSO to a plain ADIF file under
 * Android/data/pl.hamlogbridge/files/Documents/. Visible over USB/MTP and
 * shareable from the app, so nothing is lost if every upload fails.
 */
class LocalAdifWriter(private val ctx: Context) {

    fun currentFile(): File {
        val dir = File(
            ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir,
            "HamLogBridge"
        )
        if (!dir.exists()) dir.mkdirs()
        val year = ZonedDateTime.now(ZoneOffset.UTC).year
        return File(dir, "hamlogbridge-$year.adi")
    }

    @Synchronized
    fun append(record: String): Result<File> = runCatching {
        val f = currentFile()
        if (!f.exists() || f.length() == 0L) {
            f.writeText(Adif.header("1.0.0"))
        }
        f.appendText(record)
        f
    }
}
