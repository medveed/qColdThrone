package app.qcoldthrone.mobile.ui.profile

import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import app.qcoldthrone.mobile.Key
import app.qcoldthrone.mobile.R
import app.qcoldthrone.mobile.aidl.ISagerNetService
import app.qcoldthrone.mobile.bg.BaseService
import app.qcoldthrone.mobile.bg.SagerConnection
import app.qcoldthrone.mobile.database.DataStore
import app.qcoldthrone.mobile.outbound.json.JsonInput
import app.qcoldthrone.mobile.outbound.offsite.OffsiteConfigParser
import app.qcoldthrone.mobile.outbound.offsite.OffsiteConfigValidator
import app.qcoldthrone.mobile.outbound.offsite.OffsiteDocument
import app.qcoldthrone.mobile.outbound.offsite.OffsiteIssue
import app.qcoldthrone.mobile.outbound.offsite.OffsiteSeverity
import app.qcoldthrone.mobile.outbound.types.OffsiteConfig
import app.qcoldthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "Offsite JSON Config" profile editor (docs/offsite-json-config.md §8). */
class OffsiteConfigSettingsActivity : BindingSettingsActivity<OffsiteConfig>(), SagerConnection.Callback {

    override fun createEntity() = OffsiteConfig()
    override val preferencesResource = R.xml.offsite_config_preferences
    override val supportsRawJson = false

    init {
        pbm.text("name")
        pbm.text("config", Key.SERVER_CONFIG)
    }

    private var summaryPreference: Preference? = null

    /** Bound only to read the running core's selected member for the default balancer, on demand. */
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_OFFSITE)

    override fun onStart() {
        super.onStart()
        connection.connect(this, this)
    }

    override fun onStop() {
        connection.disconnect(this)
        super.onStop()
    }

    override fun onServiceConnected(service: ISagerNetService) = Unit

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) = Unit

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        summaryPreference = findPreference(KEY_SUMMARY)
        findPreference<Preference>(KEY_CHECK)?.setOnPreferenceClickListener {
            checkConfig()
            true
        }
        findPreference<Preference>(KEY_CONTENTS)?.setOnPreferenceClickListener {
            showContents()
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

    /** §8 "Contents": the document's outbounds and balancers, with the egress traffic uses marked. */
    private fun showContents() {
        val text = DataStore.profileCacheStore.getString(Key.SERVER_CONFIG).orEmpty()
        val document = OffsiteConfigParser.parse(text).document
        if (document == null) {
            showContentsDialog(getString(R.string.offsite_config_summary_invalid))
            return
        }
        val usedTag = (document.finalTag ?: document.outbounds.firstOrNull()?.tag).orEmpty()
        val balancerTag = document.balancers.firstOrNull { it.tag == usedTag }?.tag
        if (balancerTag == null) {
            showContentsDialog(contentsText(document, null))
            return
        }
        // The default exit is a balancer: ask the running core (off the main thread) which member it holds now.
        lifecycleScope.launch {
            val live = withContext(Dispatchers.IO) { queryGroupSample(balancerTag) }
            showContentsDialog(contentsText(document, live))
        }
    }

    private fun showContentsDialog(message: String) {
        MaterialAlertDialogBuilder(this@OffsiteConfigSettingsActivity)
            .setTitle(R.string.offsite_config_contents_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** The member [tag]'s group holds now, null when this profile is not the one the core running. */
    private fun queryGroupSample(tag: String): String? {
        val service = connection.service ?: return null
        val name = proxyEntity?.displayName() ?: return null
        if (service.state != BaseService.State.Connected.ordinal || service.profileName != name) return null
        return try {
            JsonInput.parseObject(service.autoSelectorGroupSample(tag)).string("selected").takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            null
        }
    }

    /** The outbounds and balancers of [document]; the exit every unmatched connection uses is marked, and for a
     *  balancer default exit the member the core holds now ([liveMember]) when it is running. */
    private fun contentsText(document: OffsiteDocument, liveMember: String?): String {
        val usedTag = (document.finalTag ?: document.outbounds.firstOrNull()?.tag).orEmpty()
        val text = StringBuilder()
        text.append(getString(R.string.offsite_config_contents_default_exit, usedTag.ifEmpty { "—" })).append("\n")
        text.append('\n').append(getString(R.string.offsite_config_contents_outbounds)).append('\n')
        for (outbound in document.outbounds) {
            val kind = when {
                outbound.isOffsite -> getString(R.string.offsite_config_contents_kind_offsite)
                outbound.outbound?.isEndpoint() == true -> getString(R.string.offsite_config_contents_kind_endpoint)
                else -> getString(R.string.offsite_config_contents_kind_standard)
            }
            text.append("• ").append(outbound.tag).append(" — ").append(outbound.protocol)
                .append(" (").append(kind).append(')')
            val address = outbound.outbound?.displayAddress().orEmpty()
            if (address.isNotEmpty()) text.append(' ').append(address)
            if (outbound.tag == usedTag) text.append(" — ").append(getString(R.string.offsite_config_contents_used))
            text.append('\n')
        }
        if (document.balancers.isNotEmpty()) {
            text.append('\n').append(getString(R.string.offsite_config_contents_balancers)).append('\n')
            for (balancer in document.balancers) {
                val members = document.outbounds
                    .filter { outbound -> balancer.selectors.any { outbound.tag.startsWith(it) } }
                    .map { it.tag }
                text.append("• ").append(balancer.tag).append(" — ").append(balancer.strategy ?: "random")
                    .append(": ").append(members.joinToString(", ").ifEmpty { "—" })
                if (balancer.tag == usedTag) {
                    text.append(" — ").append(getString(R.string.offsite_config_contents_used))
                    liveMember?.let { text.append(" · ").append(getString(R.string.offsite_config_contents_now, it)) }
                }
                balancer.fallbackTag?.takeIf { it.isNotEmpty() }?.let {
                    text.append(" · ").append(getString(R.string.offsite_config_contents_fallback, it))
                }
                text.append('\n')
            }
        }
        return text.toString().trimEnd()
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
        private const val KEY_CONTENTS = "offsiteContents"
    }

}
