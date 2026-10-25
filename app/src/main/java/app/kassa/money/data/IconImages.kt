package app.kassa.money.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Category photos saved by earlier versions, kept in the app's private files. New photos can't be added any more
 * (icons come from the catalog), but existing ones still show and travel with backups.
 */
object IconImages {
    fun load(context: Context, name: String): Bitmap? =
        File(File(context.filesDir, "icons"), File(name).name).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
}
