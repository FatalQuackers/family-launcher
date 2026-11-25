package com.fatalquackers.familylauncher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val ui = Handler(Looper.getMainLooper())

    /** When the TV wakes up, show Family Home. */
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) ui.postDelayed({ openFamily() }, 700)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val f = IntentFilter(Intent.ACTION_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenOn, f)
        } catch (_: Exception) { }
        // Just switched on: open Family Home.
        if (SystemClock.elapsedRealtime() < 5 * 60_000L) ui.postDelayed({ openFamily() }, 1500)
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOn) } catch (_: Exception) { }
        super.onDestroy()
    }

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
