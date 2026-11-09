package dev.kitchenapp.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.kitchenapp.sharedui.CookingPlatform
import dev.kitchenapp.data.database.AndroidKitchenDatabase
import dev.kitchenapp.data.database.SqlDelightActiveCookingSessionRepository
import dev.kitchenapp.data.database.SqlDelightCookingRepository
import java.util.concurrent.TimeUnit

internal const val REMINDER_CHANNEL = "cooking_reminders"
internal const val REMINDER_KIND = "reminder_kind"
internal const val REMINDER_ID = "reminder_id"
internal const val REMINDER_GENERATION = "reminder_generation"
internal const val CHECK_IN = "check_in"
internal const val LEFTOVER = "leftover"
internal val reminderLock = Any()

internal class AndroidCookingPlatform(
    private val activity: ComponentActivity,
    private val requestPermission: () -> Unit,
) : CookingPlatform {
    private val preferences = activity.getSharedPreferences("cooking_reminders", Context.MODE_PRIVATE)
    private val manager = activity.getSystemService(NotificationManager::class.java)
    private val work = WorkManager.getInstance(activity.applicationContext)
    private var onAway: (() -> Unit)? = null
    private var onReturn: (() -> Unit)? = null
    private var onOpen: ((String?) -> Unit)? = null
    private var pendingOpen: Pair<Boolean, String?>? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private var onStatus: ((String) -> Unit)? = null
    private val statusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "status") deliverStatus()
    }
    private var resumed = false
    private val focus = CookingFocusController(activity, { onAway?.invoke() }, { onReturn?.invoke() })

    fun create(intent: Intent?) {
        manager.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL, "Cooking reminders", NotificationManager.IMPORTANCE_DEFAULT))
        focus.register()
        focus.setPermissionPrompt(preferences.contains("permission_kind"))
        handleIntent(intent)
    }

    fun start() = focus.onStart()

    fun resume() {
        resumed = true
        focus.onResume()
    }

    fun pause() {
        resumed = false
        focus.onPause()
    }

    fun stop() = focus.onStop()
    fun destroy() = focus.close()

    fun handleIntent(intent: Intent?) {
        val kind = intent?.getStringExtra(REMINDER_KIND) ?: return
        val eventId = if (kind == LEFTOVER) intent.getStringExtra(REMINDER_ID) else null
        intent.removeExtra(REMINDER_KIND)
        if (onOpen == null) pendingOpen = true to eventId else onOpen?.invoke(eventId)
    }

    fun permissionResult(granted: Boolean) {
        focus.setPermissionPrompt(false)
        val action = readPendingPermission()
        val callback = permissionCallback
        permissionCallback = null
        clearPendingPermission()
        val available = granted && notificationsEnabled()
        if (callback != null) {
            callback(available)
        } else if (action != null) {
            try {
                restorePermissionAction(action, available)
            } catch (_: Exception) {
                postStatus("The reminder could not be scheduled. Cooking and leftover follow-ups remain available.")
            }
        }
    }

    override fun bindLifecycle(onAway: () -> Unit, onReturn: () -> Unit) {
        this.onAway = onAway
        this.onReturn = onReturn
        if (resumed) onReturn()
    }

    override fun unbindLifecycle() {
        onAway = null
        onReturn = null
    }

    override fun bindReminderOpen(onOpen: (String?) -> Unit) {
        this.onOpen = onOpen
        pendingOpen?.let { (_, eventId) -> onOpen(eventId) }
        pendingOpen = null
    }

    override fun unbindReminderOpen() { onOpen = null }

    override fun bindStatus(onStatus: (String) -> Unit) {
        this.onStatus = onStatus
        preferences.registerOnSharedPreferenceChangeListener(statusListener)
        deliverStatus()
    }

    override fun unbindStatus() {
        preferences.unregisterOnSharedPreferenceChangeListener(statusListener)
        onStatus = null
    }

    private fun deliverStatus() {
        val callback = onStatus ?: return
        val message = preferences.getString("status", null) ?: return
        preferences.edit().remove("status").apply()
        callback(message)
    }

    private fun postStatus(message: String) {
        preferences.edit().putString("status", message).apply()
    }

    override fun setCookingScreen(active: Boolean, focusAllowed: Boolean) =
        focus.setCookingScreen(active, focusAllowed)

    override fun checkInMinutes(sessionId: String): Int? =
        preferences.getInt("checkin:$sessionId", 0).takeIf { it == 60 || it == 120 }

    override fun setCheckInMinutes(sessionId: String, minutes: Int?, onResult: (Boolean) -> Unit) {
        require(minutes == null || minutes == 60 || minutes == 120)
        if (minutes == null) {
            preferences.edit().remove("checkin:$sessionId").apply()
            cancelCheckIn(sessionId)
            onResult(true)
        } else {
            ensureNotifications(PermissionAction(CHECK_IN, sessionId, minutes)) { available ->
                if (available) {
                    preferences.edit().putInt("checkin:$sessionId", minutes).apply()
                } else {
                    preferences.edit().remove("checkin:$sessionId").apply()
                    cancelCheckIn(sessionId)
                }
                onResult(available)
            }
        }
    }

    override fun scheduleCheckIn(sessionId: String, generation: Long, minutes: Int) {
        if (!notificationsEnabled()) return
        val request = OneTimeWorkRequestBuilder<CookingReminderWorker>()
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .setInputData(workDataOf(REMINDER_KIND to CHECK_IN, REMINDER_ID to sessionId, REMINDER_GENERATION to generation))
            .build()
        work.enqueueUniqueWork("checkin:$sessionId", ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancelCheckIn(sessionId: String) {
        synchronized(reminderLock) {
            work.cancelUniqueWork("checkin:$sessionId")
            manager.cancel("$CHECK_IN:$sessionId", 1)
        }
    }

    override fun scheduleLeftover(eventId: String, minutes: Int, onResult: (Boolean) -> Unit) {
        require(minutes == 60 || minutes == 120)
        ensureNotifications(PermissionAction(LEFTOVER, eventId, minutes)) { available ->
            if (available) {
                enqueueLeftover(eventId, minutes) { success ->
                    onResult(success)
                    postStatus(if (success) "Leftover reminder scheduled."
                    else "The reminder could not be scheduled. Record leftovers later from Cook.")
                }
            } else {
                onResult(false)
            }
        }
    }

    override fun cancelLeftover(eventId: String) {
        synchronized(reminderLock) {
            work.cancelUniqueWork("leftover:$eventId")
            manager.cancel("leftover:$eventId", 1)
        }
    }

    private data class PermissionAction(val kind: String, val id: String, val minutes: Int)

    private fun ensureNotifications(action: PermissionAction, callback: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            if (permissionCallback != null || preferences.contains("permission_kind")) {
                callback(false)
                return
            }
            val saved = preferences.edit()
                .putString("permission_kind", action.kind)
                .putString("permission_id", action.id)
                .putInt("permission_minutes", action.minutes)
                .commit()
            if (!saved) { callback(false); return }
            permissionCallback = callback
            focus.setPermissionPrompt(true)
            try {
                requestPermission()
            } catch (_: Exception) {
                permissionCallback = null
                focus.setPermissionPrompt(false)
                clearPendingPermission()
                callback(false)
            }
        } else {
            callback(notificationsEnabled())
        }
    }

    private fun readPendingPermission(): PermissionAction? {
        val kind = preferences.getString("permission_kind", null) ?: return null
        val id = preferences.getString("permission_id", null) ?: return null
        val minutes = preferences.getInt("permission_minutes", 0)
        if (kind !in setOf(CHECK_IN, LEFTOVER) || minutes !in setOf(60, 120)) return null
        return PermissionAction(kind, id, minutes)
    }

    private fun clearPendingPermission() {
        preferences.edit().remove("permission_kind").remove("permission_id").remove("permission_minutes").commit()
    }

    private fun restorePermissionAction(action: PermissionAction, available: Boolean) {
        if (!available) {
            if (action.kind == CHECK_IN) {
                preferences.edit().remove("checkin:${action.id}").apply()
                cancelCheckIn(action.id)
            }
            postStatus("Notifications are unavailable. Cooking and leftover follow-ups remain available.")
            return
        }
        val database = AndroidKitchenDatabase.open(activity.applicationContext)
        if (action.kind == CHECK_IN) {
            val session = SqlDelightActiveCookingSessionRepository(database).getActive()
            if (session?.sessionId == action.id) {
                preferences.edit().putInt("checkin:${action.id}", action.minutes).apply()
                postStatus("Cook check-in enabled.")
            } else {
                postStatus("The cooking session has ended; no check-in was scheduled.")
            }
        } else {
            val pending = SqlDelightCookingRepository(database).getHistory()
                .any { it.event.id == action.id && it.leftoverFollowUpPending }
            if (!pending) {
                postStatus("The leftover follow-up is no longer pending.")
                return
            }
            enqueueLeftover(action.id, action.minutes) { success ->
                postStatus(if (success) "Leftover reminder scheduled."
                else "The reminder could not be scheduled. Record leftovers later from Cook.")
            }
        }
    }

    private fun enqueueLeftover(eventId: String, minutes: Int, callback: (Boolean) -> Unit) {
        val pending = runCatching {
            val database = AndroidKitchenDatabase.open(activity.applicationContext)
            SqlDelightCookingRepository(database).getHistory()
                .any { it.event.id == eventId && it.leftoverFollowUpPending }
        }.getOrDefault(false)
        if (!pending) { callback(false); return }
        val request = OneTimeWorkRequestBuilder<CookingReminderWorker>()
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .setInputData(workDataOf(REMINDER_KIND to LEFTOVER, REMINDER_ID to eventId))
            .build()
        try {
            val operation = work.enqueueUniqueWork("leftover:$eventId", ExistingWorkPolicy.KEEP, request)
            operation.result.addListener(
                { callback(runCatching { operation.result.get(); true }.getOrDefault(false)) },
                ContextCompat.getMainExecutor(activity),
            )
        } catch (_: Exception) {
            callback(false)
        }
    }

    private fun notificationsEnabled(): Boolean =
        manager.areNotificationsEnabled() &&
            (manager.getNotificationChannel(REMINDER_CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_NONE) != NotificationManager.IMPORTANCE_NONE
}
