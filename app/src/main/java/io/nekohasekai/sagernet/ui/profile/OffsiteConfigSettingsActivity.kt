package app.qcoldthrone.mobile.ui.profile

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import app.qcoldthrone.mobile.Key
import app.qcoldthrone.mobile.R
import app.qcoldthrone.mobile.database.DataStore
import app.qcoldthrone.mobile.outbound.offsite.OffsiteConfigParser
import app.qcoldthrone.mobile.outbound.offsite.OffsiteConfigValidator
import app.qcoldthrone.mobile.outbound.offsite.OffsiteIssue
import app.qcoldthrone.mobile.outbound.offsite.OffsiteSeverity
import app.qcoldthrone.mobile.outbound.types.OffsiteConfig
import app.qcoldthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder

/** The "Offsite JSON Config" profile editor (docs/offsite-json-config.md §8). */
class OffsiteConfigSettingsActivity : BindingSettingsActivity<OffsiteConfig>() {

    override fun createEntity() = OffsiteConfig()
    override val preferencesResource = R.xml.offsite_config_preferences
    override val supportsRawJson = false

    init {
        pbm.text("name")
        pbm.text("config", Key.SERVER_CONFIG)
    }

    private var summaryPreference: Preference? = null

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        summaryPreference = findPreference(KEY_SUMMARY)
        findPreference<Preference>(KEY_CHECK)?.setOnPreferenceClickListener {
            checkConfig()
            true
        }
        updateSummary()
    }

    override fun onResume() {
        super.onResume()
        updateSummary()
    }

    /** §8 "Check config": run the parser and validator over the cached document without saving. */
    private fun checkConfig() {
        val text = DataStore.profileCacheStore.getString(Key.SERVER_CONFIG).orEmpty()
        val parsed = OffsiteConfigParser.parse(text)
        val issues = if (parsed.document != null) {
            OffsiteConfigValidator.validate(parsed.document, text.length)
        } else {
            parsed.issues
        }
        val message = if (issues.isEmpty()) {
            getString(R.string.offsite_config_no_issues)
        } else {
            issues.joinToString("\n") { formatIssue(it) }
        }
        MaterialAlertDialogBuilder(this@OffsiteConfigSettingsActivity)
            .setTitle(R.string.offsite_config_issues_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatIssue(issue: OffsiteIssue): String {
        val severity = getString(
            if (issue.severity == OffsiteSeverity.Error) R.string.offsite_config_error
            else R.string.offsite_config_warning
        )
        val detail = if (issue.path.isEmpty()) issue.message
        else getString(R.string.offsite_config_issue_path, issue.path, issue.message)
        return getString(R.string.offsite_config_issue_line, severity, detail)
    }

    /** §8 summary: outbound/offsite/rule/balancer counts, refreshed whenever the cache changes. */
    private fun updateSummary() {
        val preference = summaryPreference ?: return
        val text = DataStore.profileCacheStore.getString(Key.SERVER_CONFIG).orEmpty()
        val document = OffsiteConfigParser.parse(text).document
        preference.summary = if (document == null) {
            getString(R.string.offsite_config_summary_invalid)
        } else {
            getString(
                R.string.offsite_config_summary,
                document.outbounds.size,
                document.outbounds.count { it.isOffsite },
                document.rules.size,
                document.balancers.size,
            )
        }
    }

    companion object {
        private const val KEY_SUMMARY = "offsiteSummary"
        private const val KEY_CHECK = "offsiteCheck"
    }

}
