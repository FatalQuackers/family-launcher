package com.fatalquackers.familylauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Makes the remote's Home button open Family Home.
 *  1. Catches the Home key itself (main method).
 *     Short press -> Family Home. Long press -> Google TV settings.
 *  2. Backup: if Google's home screen still appears, brings Family Home forward.
 * "Google TV home" inside Family Home pauses (2) until the next Home press
 * or until another app is opened.
 */
class HomeWatcher : AccessibilityService() {

    private var homeDownAt = 0L

    private fun prefs() = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    private fun openFamily() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try { startActivity(i) } catch (_: Exception) { }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val e = event ?: return false
        if (e.keyCode != KeyEvent.KEYCODE_HOME) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) homeDownAt = System.currentTimeMillis()
            KeyEvent.ACTION_UP -> {
                prefs().edit().putLong("allowStockUntil", 0L).apply()
                if (System.currentTimeMillis() - homeDownAt > 700) {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { openFamily() }
                } else openFamily()
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val prefs = prefs()
        if (pkg in MainActivity.STOCK) {
            if (System.currentTimeMillis() < prefs.getLong("allowStockUntil", 0L)) return
            openFamily()
        } else if (pkg != packageName && pkg != "com.android.systemui" && pkg != "android") {
            if (prefs.getLong("allowStockUntil", 0L) != 0L) {
                prefs.edit().putLong("allowStockUntil", 0L).apply()
            }
        }
    }

    override fun onInterrupt() {}
}
