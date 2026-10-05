package ir.mahditavakoli.mia.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ir.mahditavakoli.mia.MainActivity
import ir.mahditavakoli.mia.R
import ir.mahditavakoli.mia.data.model.RepoIssue

/**
 * The one notification MIA posts: "the agent finished this".
 *
 * Everything here degrades to doing nothing rather than throwing. It is called from a background
 * worker on a timer, where a missing runtime permission or a user-disabled channel is an ordinary
 * state, not a failure worth retrying — the work itself is still done on GitHub, and the app
 * shows it the next time it is opened.
 */
object AgentNotifications {

    const val CHANNEL_ID = "agent_completions"

    /** Extras a tapped notification carries, so the app can open the issue it is about. */
    const val EXTRA_PROJECT = "ir.mahditavakoli.mia.extra.PROJECT"
    const val EXTRA_ISSUE_NUMBER = "ir.mahditavakoli.mia.extra.ISSUE_NUMBER"

    /**
     * Registers the channel. Safe to call on every launch — creating a channel that exists is a
     * no-op, and the user's own choices about it survive.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "کارهای انجام‌شدهٔ ایجنت",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "وقتی ایجنت TEC کار یک ایشو را تمام می‌کند"
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * Whether a notification posted right now would actually be shown. Checked before doing the
     * work of building one, and again by [notifyFinished] before posting.
     */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Posts one notification for one finished issue. [projectName] is what the tap opens.
     *
     * @return true if it was posted.
     */
    // POST_NOTIFICATIONS is checked by [canPost] on the first line, and the post itself is wrapped
    // against the SecurityException that a revoke between the two would raise. Lint does not follow
    // the check across the call, so it is suppressed here rather than duplicated inline.
    @SuppressLint("MissingPermission")
    fun notifyFinished(context: Context, projectName: String, issue: RepoIssue): Boolean {
        if (!canPost(context)) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            // The app is normally already running when this lands; CLEAR_TOP + the activity's
            // singleTop launch mode reuse that instance and deliver the extras to onNewIntent
            // instead of stacking a second copy of the app behind the first.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PROJECT, projectName)
            putExtra(EXTRA_ISSUE_NUMBER, issue.number)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(projectName, issue.number),
            intent,
            // UPDATE_CURRENT so a re-post for the same issue carries the current extras rather
            // than whatever the first one was created with.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(issue.title)
            .setContentText(FINISHED_BODY)
            .setStyle(NotificationCompat.BigTextStyle().bigText(FINISHED_BODY))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context)
                .notify(notificationId(projectName, issue.number), notification)
            true
            // A SecurityException is still possible between the check above and the post (the
            // permission can be revoked in between); it means "not shown", not "crash".
        }.getOrDefault(false)
    }

    private const val FINISHED_BODY = "TEC این کار را انجام داد"

    /** Stable per project+issue, so the same finished issue never stacks two notifications. */
    private fun notificationId(projectName: String, issueNumber: Int): Int =
        (projectName.hashCode() * 31 + issueNumber)
}
