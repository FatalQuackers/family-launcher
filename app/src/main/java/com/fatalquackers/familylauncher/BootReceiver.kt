package com.fatalquackers.familylauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Opens Family Home when the TV starts up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == "android.intent.action.LOCKED_BOOT_COMPLETED") {
            try {
                c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { }
        }
    }
}
