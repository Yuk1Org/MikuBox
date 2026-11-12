package com.miku.ray.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.miku.ray.AppConfig
import com.miku.ray.handler.SubscriptionUpdater
import com.miku.ray.util.LogUtil

/**
 * App-update hook only.
 *
 * The tunnel never starts on its own: there is no boot start, no sticky
 * restart and no recovery alarm. This receiver survives for the one piece of
 * housekeeping an update needs — re-arming the subscription schedule — and
 * does not touch the VPN.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (context == null) return
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        LogUtil.i(AppConfig.TAG, "BootReceiver: app updated, refreshing subscription schedule")
        SubscriptionUpdater.sync(context)
    }
}
