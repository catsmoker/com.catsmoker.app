package com.catsmoker.app.features.gamingtools.tools.dns

import android.content.Context
import com.topjohnwu.superuser.Shell

object DnsFeature {
    fun applyRootDns(context: Context, dns1: String, dns2: String) {
        if (!isValidIp(dns1) || !isValidIp(dns2)) return
        
        Shell.cmd("setprop net.dns1 $dns1").exec()
        Shell.cmd("setprop net.dns2 $dns2").exec()
        Shell.cmd("setprop net.eth0.dns1 $dns1").exec()
        Shell.cmd("setprop net.eth0.dns2 $dns2").exec()
    }

    fun resetRootDns(context: Context) {
        Shell.cmd("setprop net.dns1 \"\"").exec()
        Shell.cmd("setprop net.dns2 \"\"").exec()
    }

    private fun isValidIp(ip: String): Boolean {
        val regex = "^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$".toRegex()
        return regex.matches(ip)
    }
}
