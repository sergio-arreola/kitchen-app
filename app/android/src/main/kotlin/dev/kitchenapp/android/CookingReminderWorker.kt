package dev.kitchenapp.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.kitchenapp.core.application.ClaimCookingCheckIn
import dev.kitchenapp.core.application.ClaimLeftoverFollowUpReminder
import dev.kitchenapp.data.database.AndroidKitchenDatabase
import dev.kitchenapp.data.database.SqlDelightActiveCookingSessionRepository
import dev.kitchenapp.data.database.SqlDelightCookingRepository

class CookingReminderWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val kind = inputData.getString(REMINDER_KIND) ?: return Result.failure()
        val id = inputData.getString(REMINDER_ID) ?: return Result.failure()
        if (kind != CHECK_IN && kind != LEFTOVER) return Result.failure()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        synchronized(reminderLock) {
            if (!manager.areNotificationsEnabled() ||
                (manager.getNotificationChannel(REMINDER_CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_NONE) == NotificationManager.IMPORTANCE_NONE ||
                (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ) return Result.success()

            val intent = Intent(applicationContext, MainActivity::class.java)
                .setData(Uri.Builder().scheme("kitchen").authority("reminder").appendPath(kind).appendPath(id).build())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(REMINDER_KIND, kind)
                .putExtra(REMINDER_ID, id)
            val pendingIntent = PendingIntent.getActivity(
                applicationContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(applicationContext, REMINDER_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (kind == CHECK_IN) "Still cooking?" else "Record leftovers")
                .setContentText(if (kind == CHECK_IN) "Return to your cook when you're ready." else "Review leftovers from your meal.")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            val database = AndroidKitchenDatabase.open(applicationContext)
            val claimed = if (kind == CHECK_IN) {
                val generation = inputData.getLong(REMINDER_GENERATION, -1)
                ClaimCookingCheckIn(SqlDelightActiveCookingSessionRepository(database)).claim(id, generation)
            } else {
                ClaimLeftoverFollowUpReminder(SqlDelightCookingRepository(database)).claim(id)
            }
            if (claimed) {
                try {
                    manager.notify("$kind:$id", 1, notification)
                } catch (_: SecurityException) {
                    // Permission can be revoked between the check and posting.
                }
            }
        }
        return Result.success()
    }
}
