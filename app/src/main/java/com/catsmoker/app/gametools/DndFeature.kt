package com.catsmoker.app.gametools

import android.app.NotificationManager
import android.content.Context
import android.widget.CompoundButton
import com.catsmoker.app.R

class DndFeature(
    private val context: Context,
    private val requestPolicyAccess: () -> Unit,
    private val saveState: (Boolean) -> Unit,
    private val showMessage: (Int) -> Unit
) {

    fun bind(toggle: CompoundButton) {
        toggle.setOnCheckedChangeListener { view, isChecked ->
            saveState(isChecked)
            if (isChecked) {
                if (!hasNotificationPolicyAccess()) {
                    view.isChecked = false
                    requestPolicyAccess()
                } else {
                    setDndMode(enable = true)
                }
            } else {
                setDndMode(enable = false)
            }
        }
    }

    fun sync(toggle: CompoundButton) {
        toggle.setOnCheckedChangeListener(null)
        val dndActive = isDndActive()
        toggle.isChecked = dndActive
        saveState(dndActive)
        bind(toggle)
    }

    private fun setDndMode(enable: Boolean) {
        val nm = notificationManager ?: return
        if (!hasNotificationPolicyAccess()) return

        if (enable) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            showMessage(R.string.dnd_enabled)
        } else {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            showMessage(R.string.dnd_disabled)
        }
    }

    private val notificationManager: NotificationManager?
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun hasNotificationPolicyAccess(): Boolean =
        notificationManager?.isNotificationPolicyAccessGranted == true

    private fun isDndActive(): Boolean {
        return notificationManager?.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }
}
