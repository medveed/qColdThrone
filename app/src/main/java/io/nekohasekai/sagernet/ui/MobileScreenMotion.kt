package app.qcoldthrone.mobile.ui

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import androidx.fragment.app.Fragment
import app.qcoldthrone.mobile.R

/** Match the fast activity fade without sliding the settings content. */
object MobileScreenMotion {
    fun create(fragment: Fragment, nextAnim: Int): Animator? {
        val view = fragment.view ?: return null
        val incoming = nextAnim == R.anim.settings_enter || nextAnim == R.anim.settings_pop_enter
        if (nextAnim !in listOf(R.anim.settings_enter, R.anim.settings_exit, R.anim.settings_pop_enter, R.anim.settings_pop_exit)) return null
        view.translationX = 0f
        return ObjectAnimator.ofFloat(view, View.ALPHA, if (incoming) 0f else 1f, if (incoming) 1f else 0f).apply {
            duration = 90
        }
    }
}
