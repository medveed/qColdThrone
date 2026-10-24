package app.qcoldthrone.mobile.ui

import android.content.Intent
import android.os.Bundle
import app.qcoldthrone.mobile.R
import app.qcoldthrone.mobile.database.ProxyEntity

class ProfileSelectActivity : ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_PROFILE_ID = "id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) return
        // The typed getParcelableExtra is API 33+; below it the deprecated overload is the only one.
        val selected: ProxyEntity? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_SELECTED, ProxyEntity::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra<ProxyEntity>(EXTRA_SELECTED)
        }

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                // both callers fill fixed-server slots (front / landing proxy, chain hops)
                ConfigurationFragment.forSelection(selected, R.string.select_profile, hideAutoSelectors = true)
            )
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_ID, profileId)
        })
        finish()
    }

}