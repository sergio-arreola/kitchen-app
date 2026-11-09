package dev.kitchenapp.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager

/**
 * Keeps the activity window awake only while an eligible cooking screen is resumed.
 * Departure and return callbacks are separate from transient activity pauses.
 */
internal class CookingFocusController(
    private val activity: Activity,
    private val onAway: () -> Unit,
    private val onReturn: () -> Unit,
) {
    private var resumed = false
    private var started = false
    private var cookScreen = false
    private var focusAllowed = false
    private var away = false
    private var permissionPrompt = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF && started) {
                leave()
            }
        }
    }

    fun register() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(screenOffReceiver, filter)
        }
    }

    fun onStart() {
        started = true
    }

    fun onResume() {
        resumed = true
        if (isInteractive()) {
            away = false
            onReturn()
        } else {
            leave()
        }
        updateWindow()
    }

    fun onPause() {
        resumed = false
        updateWindow()
    }

    fun onStop() {
        started = false
        if (!activity.isChangingConfigurations && !permissionPrompt) leave()
    }

    fun close() {
        resumed = false
        updateWindow()
        activity.unregisterReceiver(screenOffReceiver)
    }

    fun setPermissionPrompt(inProgress: Boolean) {
        permissionPrompt = inProgress
    }

    fun setCookingScreen(active: Boolean, allowed: Boolean) {
        cookScreen = active
        focusAllowed = allowed
        updateWindow()
    }

    private fun leave() {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!away) {
            away = true
            onAway()
        }
    }

    private fun updateWindow() {
        if (resumed && !away && isInteractive() && cookScreen && focusAllowed) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun isInteractive(): Boolean =
        (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
}
