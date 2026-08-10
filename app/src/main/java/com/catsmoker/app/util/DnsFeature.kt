package com.catsmoker.app.util

import com.topjohnwu.superuser.Shell

object DnsFeature {
    fun applyRootDns(dns1: String, dns2: String) {
        Shell.cmd("setprop net.dns1 $dns1; setprop net.dns2 $dns2").submit()
    }

    fun resetRootDns() {
        Shell.cmd("setprop net.dns1 \"\"; setprop net.dns2 \"\"").submit()
    }
}
