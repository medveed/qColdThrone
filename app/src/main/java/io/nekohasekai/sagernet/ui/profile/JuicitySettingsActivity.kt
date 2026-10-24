package app.qcoldthrone.mobile.ui.profile

import androidx.preference.PreferenceFragmentCompat
import app.qcoldthrone.mobile.R
import app.qcoldthrone.mobile.outbound.types.Juicity

class JuicitySettingsActivity : BindingSettingsActivity<Juicity>() {

    override fun createEntity() = Juicity()
    override val preferencesResource = R.xml.juicity_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("uuid")
        pbm.text("password")
        TlsBlock.bind(pbm)
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password")
        TlsBlock.setup(this, mustTls = true)
    }

}
