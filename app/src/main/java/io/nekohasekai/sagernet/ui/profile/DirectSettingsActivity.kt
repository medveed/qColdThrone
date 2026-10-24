package app.qcoldthrone.mobile.ui.profile

import app.qcoldthrone.mobile.R
import app.qcoldthrone.mobile.outbound.types.Direct

/** A direct outbound: a name and the dial fields only. */
class DirectSettingsActivity : BindingSettingsActivity<Direct>() {

    override fun createEntity() = Direct()
    override val preferencesResource = R.xml.direct_preferences

    init {
        pbm.text("name")
        DialBlock.bind(pbm)
    }

}
