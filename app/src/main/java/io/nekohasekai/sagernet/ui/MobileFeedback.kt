package app.qcoldthrone.mobile.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View

object MobileFeedback {
    fun apply(view: View, radius: Float = 5f, circle: Boolean = false, white: Boolean = false) {
        if(view.background is RippleDrawable) return
        val mask=GradientDrawable().apply {
            setColor(Color.WHITE)
            if(circle) shape=GradientDrawable.OVAL
            else cornerRadius=radius*view.resources.displayMetrics.density
        }
        val colour=if(MobilePalette.light && !white) Color.argb(28,0,0,0) else Color.argb(35,255,255,255)
        view.background=RippleDrawable(ColorStateList.valueOf(colour),view.background,mask)
    }
}
