package dev.krishnagurram.kassa.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * Category pictures picked by the user. Each is cropped to a centered square, scaled to 192px and saved as a PNG
 * in the app's private files, so the original photo is never touched or kept.
 */
object IconImages {
    private const val SIZE = 192

    private fun dir(context: Context) = File(context.filesDir, "icons").apply { mkdirs() }

    fun file(context: Context, name: String) = File(dir(context), name)

    fun save(context: Context, uri: Uri): String {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val name = "${UUID.randomUUID()}.png"
        file(context, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    fun load(context: Context, name: String): Bitmap? = file(context, name).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
}
