package com.mmcdole.tether.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.mmcdole.tether.MainActivity
import com.mmcdole.tether.R
import com.mmcdole.tether.data.session.SessionState
import com.mmcdole.tether.data.world.World
import com.mmcdole.tether.mud.rules.NotifyRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionNotifications @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_SESSION, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Session")
                    .setShowBadge(false)
                    .build(),
                // Trigger notifications.
                NotificationChannelCompat.Builder(CHANNEL_MESSAGES, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Messages")
                    .build(),
            ),
        )
    }

    private fun openSession(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SESSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ongoing(state: SessionState): Notification {
        val open = openSession()
        val disconnect = PendingIntent.getService(
            context,
            0,
            Intent(context, SessionService::class.java).setAction(SessionService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Open", open)
            .addAction(0, "Disconnect", disconnect)
        // The system ticks the uptime, so nothing has to post every second.
        if (state is SessionState.Connected) {
            builder.setWhen(state.since.toEpochMilli()).setShowWhen(true).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    fun update(state: SessionState) {
        post(ONGOING_ID, ongoing(state))
    }

    private val conversations = Conversations()
    private val you = Person.Builder().setName("You").build()

    /** A trigger's message, added to its sender's conversation. */
    fun received(world: World, request: NotifyRequest) {
        post(conversations.received(world, request, System.currentTimeMillis()), quiet = false)
    }

    /** Shows your reply in the conversation, which also tells Android the reply went through. */
    fun replied(target: ReplyTarget, text: String) {
        post(conversations.replied(target, text, System.currentTimeMillis()), quiet = true)
    }

    fun notConnected(target: ReplyTarget) {
        post(conversations.status(target, "Not connected"), quiet = true)
    }

    /** Reposts the conversation unchanged, to clear the reply spinner after an empty reply. */
    fun unchanged(target: ReplyTarget) {
        post(conversations.status(target, null), quiet = true)
    }

    fun dismissed(key: ConversationKey) = conversations.remove(key)

    /** Clears a world's Messages notifications, as when its session is opened. */
    fun clearWorld(worldId: Long) {
        conversations.removeWorld(worldId)
        val prefix = ConversationKey.worldPrefix(worldId)
        manager.activeNotifications
            .filter { it.id == MESSAGE_ID && it.tag?.startsWith(prefix) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun post(conversation: Conversation, quiet: Boolean) {
        val sender = Person.Builder()
            .setName(conversation.key.sender)
            .setKey(conversation.key.tag)
            .build()
        val style = NotificationCompat.MessagingStyle(you)
            .setConversationTitle(conversation.title)
            .setGroupConversation(false)
        for (message in conversation.messages) {
            style.addMessage(message.text, message.time, if (message.fromYou) null else sender)
        }
        val open = openSession()
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setSubText(conversation.status ?: conversation.worldName.takeIf { conversation.hasSender })
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setDeleteIntent(dismissIntent(conversation.key))
            .setAutoCancel(true)
            // A reply or status update shouldn't buzz again; a new message should.
            .setOnlyAlertOnce(quiet)
        val target = conversation.replyTarget
        if (target != null) builder.addAction(replyAction(target)) else builder.addAction(0, "Open", open)
        post(conversation.key.tag, MESSAGE_ID, builder.build())
    }

    private fun replyAction(target: ReplyTarget): NotificationCompat.Action {
        val input = RemoteInput.Builder(ReplyReceiver.KEY_TEXT)
            // Shown in front of what you type, so you can see what will be sent.
            .setLabel(target.prefix)
            .build()
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            ReplyReceiver.replyIntent(context, target),
            // Mutable so Android can add the typed text.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Action.Builder(0, "Reply", intent)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
        if (Build.VERSION.SDK_INT >= 31) builder.setAuthenticationRequired(target.requiresUnlock)
        return builder.build()
    }

    private fun dismissIntent(key: ConversationKey): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        ReplyReceiver.dismissIntent(context, key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(id: Int, notification: Notification) = post(null, id, notification)

    private fun post(tag: String?, id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) manager.notify(tag, id, notification)
    }

    private fun SessionState.title(): String = when (this) {
        SessionState.Idle -> "Disconnected"
        is SessionState.Connecting -> "Connecting to ${world.name}"
        is SessionState.Connected -> "Connected to ${world.name}"
        is SessionState.Reconnecting -> "Reconnecting to ${world.name}"
    }

    companion object {
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_MESSAGES = "messages"
        const val ONGOING_ID = 1

        // Every conversation uses this id; its tag tells them apart.
        private const val MESSAGE_ID = 2
    }
}
