package app.qcoldthrone.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Flat country artwork independent of the device's emoji font. */
object MobileCountryFlags {
    private val images = mutableMapOf<String, Bitmap?>()

    fun bitmap(context: Context, code: String): Bitmap? = images.getOrPut(code) {
        runCatching {
            context.assets.open("flags/$code.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
}
