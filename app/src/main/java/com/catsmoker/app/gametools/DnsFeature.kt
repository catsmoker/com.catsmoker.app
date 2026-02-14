package com.catsmoker.app.gametools

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.R
import com.catsmoker.app.databinding.ActivityGameFeaturesScreenBinding
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsFeature(
    private val activity: AppCompatActivity,
    private val context: Context,
    private val binding: ActivityGameFeaturesScreenBinding,
    private val isRooted: () -> Boolean,
    private val showSnackbar: (String) -> Unit
) {
    private val dnsPrefs: SharedPreferences
        get() = context.getSharedPreferences(FeaturesActivity.DNS_PREFS, Context.MODE_PRIVATE)

    fun setupDnsFeature() {
        binding.dnsEditText.setText(dnsPrefs.getString(FeaturesActivity.KEY_CUSTOM_DNS, ""))

        var savedDnsMethodId = dnsPrefs.getInt(FeaturesActivity.KEY_DNS_METHOD, R.id.radio_dns_root)
        if (savedDnsMethodId != R.id.radio_dns_root && savedDnsMethodId != R.id.radio_dns_vpn) {
            savedDnsMethodId = R.id.radio_dns_root
        }

        setupDnsSpinners(dnsPrefs)

        binding.dnsMethodRadioGroup.check(savedDnsMethodId)
        updateDnsUI()

        binding.dnsMethodRadioGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) updateDnsUI()
        }

        binding.btnApplyDns.setOnClickListener { applyDnsChanges() }
    }

    fun onRootStatusChanged(rooted: Boolean) {
        val rootBtn = binding.dnsMethodRadioGroup.findViewById<View>(R.id.radio_dns_root)
        rootBtn.isEnabled = rooted
        if (!rooted && binding.dnsMethodRadioGroup.checkedButtonId == R.id.radio_dns_root) {
            binding.dnsMethodRadioGroup.check(R.id.radio_dns_vpn)
        }
    }

    private fun setupDnsSpinners(prefs: SharedPreferences) {
        val adapter = ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, DNS_OPTIONS)
        binding.dnsSpinnerRoot.setAdapter(adapter)
        binding.dnsSpinnerVpn.setAdapter(adapter)

        val savedIndex = prefs.getInt(FeaturesActivity.KEY_DNS_PROVIDER_INDEX, 0)
        if (savedIndex < DNS_OPTIONS.size) {
            val selectedText = DNS_OPTIONS[savedIndex]
            binding.dnsSpinnerRoot.setText(selectedText, false)
            binding.dnsSpinnerVpn.setText(selectedText, false)
        }

        binding.dnsSpinnerRoot.setOnItemClickListener { _, _, _, _ -> updateDnsUI() }
        binding.dnsSpinnerVpn.setOnItemClickListener { _, _, _, _ -> updateDnsUI() }
    }

    private fun updateDnsUI() {
        val checkedId = binding.dnsMethodRadioGroup.checkedButtonId
        val isRootMode = (checkedId == R.id.radio_dns_root)

        val activeSpinner = if (isRootMode) binding.dnsSpinnerRoot else binding.dnsSpinnerVpn
        val selectedOption = activeSpinner.text.toString()
        val isCustomSelected = selectedOption == DNS_CUSTOM

        binding.rootDnsOptions.visibility = if (isRootMode) View.VISIBLE else View.GONE
        binding.vpnDnsTextInputLayout.visibility = if (isRootMode) View.GONE else View.VISIBLE
        binding.vpnCustomDnsLayout.visibility = if (isCustomSelected) View.VISIBLE else View.GONE
    }

    private fun getDnsOptionIndex(option: String): Int = DNS_OPTIONS.indexOf(option)

    private fun applyDnsChanges() {
        val methodId = binding.dnsMethodRadioGroup.checkedButtonId
        val editor = dnsPrefs.edit()

        editor.putInt(FeaturesActivity.KEY_DNS_METHOD, methodId)
        val selectedIndex = if (methodId == R.id.radio_dns_root) {
            getDnsOptionIndex(binding.dnsSpinnerRoot.text.toString())
        } else {
            getDnsOptionIndex(binding.dnsSpinnerVpn.text.toString())
        }
        editor.putInt(FeaturesActivity.KEY_DNS_PROVIDER_INDEX, selectedIndex)

        if (methodId == R.id.radio_dns_root) {
            applyRootDns()
            editor.apply()
        } else {
            applyVpnDns(editor)
            showSnackbar(activity.getString(R.string.dns_saved_vpn))
        }
    }

    private fun applyRootDns() {
        if (!isRooted()) {
            showSnackbar(activity.getString(R.string.root_access_not_detected))
            return
        }

        val cmd = when (val selected = binding.dnsSpinnerRoot.text.toString()) {
            DNS_CUSTOM -> {
                val customIp = binding.dnsEditText.text?.toString()?.trim() ?: ""
                if (customIp.isEmpty()) {
                    showSnackbar(activity.getString(R.string.please_enter_valid_ip))
                    return
                }
                val (dns1, dns2) = customIp
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
                "setprop net.dns1 $dns1; setprop net.dns2 $dns2"
            }
            else -> {
                val (dns1, dns2) = dnsPairForOption(selected)
                if (dns1.isEmpty()) {
                    "setprop net.dns1 \"\"; setprop net.dns2 \"\""
                } else {
                    "setprop net.dns1 $dns1; setprop net.dns2 $dns2"
                }
            }
        }

        activity.lifecycleScope.launch(Dispatchers.IO) {
            val success = try { Shell.cmd(cmd).exec().isSuccess } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                showSnackbar(
                    if (success) activity.getString(R.string.dns_applied_root)
                    else activity.getString(R.string.root_command_failed)
                )
            }
        }
    }

    private fun applyVpnDns(editor: SharedPreferences.Editor) {
        val selected = binding.dnsSpinnerVpn.text.toString()
        val dnsToSave = when {
            selected == DNS_CUSTOM -> binding.dnsEditText.text.toString()
            else -> dnsPairForOption(selected).let { (dns1, dns2) ->
                if (dns1.isEmpty()) "" else "$dns1,$dns2"
            }
        }
        editor.putString(FeaturesActivity.KEY_CUSTOM_DNS, dnsToSave)
        editor.apply()
    }

    private fun dnsPairForOption(option: String): Pair<String, String> = when (option) {
        DNS_GOOGLE -> "8.8.8.8" to "8.8.4.4"
        DNS_CLOUDFLARE -> "1.1.1.1" to "1.0.0.1"
        else -> "" to ""
    }

    companion object {
        private const val DNS_CUSTOM = "Custom"
        private const val DNS_GOOGLE = "Google (8.8.8.8)"
        private const val DNS_CLOUDFLARE = "Cloudflare (1.1.1.1)"
        private val DNS_OPTIONS = arrayOf(
            "Default (DHCP)",
            DNS_CUSTOM,
            DNS_GOOGLE,
            DNS_CLOUDFLARE
        )
    }
}
