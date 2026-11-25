package com.fatalquackers.familylauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV doesn't let you pick a different home app, so this watches for
 * Google's home screen opening and brings Family Home to the front instead.
 * "Google TV home" in Family Home pauses this until another app is opened.
 */
class HomeWatcher : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

        if (pkg in MainActivity.STOCK) {
            val cls = e.className?.toString() ?: ""
            if (!cls.contains("Home") && !cls.endsWith("MainActivity")) return
            if (System.currentTimeMillis() < prefs.getLong("allowStockUntil", 0L)) return
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            try { startActivity(i) } catch (_: Exception) { }
        } else if (pkg != packageName && pkg != "com.android.systemui" && pkg != "android") {
            if (prefs.getLong("allowStockUntil", 0L) != 0L) {
                prefs.edit().putLong("allowStockUntil", 0L).apply()
            }
        }
    }

    override fun onInterrupt() {}
}
