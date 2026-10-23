package com.mmcdole.tether.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.RemoteInput
import com.mmcdole.tether.data.session.ReplyResult
import com.mmcdole.tether.data.session.SessionManager
import com.mmcdole.tether.data.session.replyCommand
import com.mmcdole.tether.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Handles a Messages notification's Reply, and forgets a conversation when it's swiped away. */
@AndroidEntryPoint
class ReplyReceiver : BroadcastReceiver() {
    @Inject lateinit var session: SessionManager
    @Inject lateinit var notifications: SessionNotifications
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REPLY -> reply(intent)
            ACTION_DISMISS -> {
                val worldId = intent.getLongExtra(EXTRA_WORLD_ID, -1)
                val sender = intent.getStringExtra(EXTRA_SENDER) ?: return
                notifications.dismissed(ConversationKey(worldId, sender))
            }
        }
    }

    private fun reply(intent: Intent) {
        val target = intent.replyTarget() ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString().orEmpty()
        val command = replyCommand(target.prefix, text)
        if (command == null) {
            // Nothing typed. Posting again still stops the spinner.
            notifications.unchanged(target)
            return
        }
        // The session runs elsewhere; keep the broadcast alive until it has taken the reply.
        val pending = goAsync()
        scope.launch {
            try {
                when (session.reply(target.worldId, command)) {
                    ReplyResult.Sent, ReplyResult.Queued -> notifications.replied(target, text.trim())
                    ReplyResult.NotConnected -> notifications.notConnected(target)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val KEY_TEXT = "reply_text"
        private const val ACTION_REPLY = "com.mmcdole.tether.action.REPLY"
        private const val ACTION_DISMISS = "com.mmcdole.tether.action.DISMISS_MESSAGE"
        private const val EXTRA_WORLD_ID = "world_id"
        private const val EXTRA_WORLD_NAME = "world_name"
        private const val EXTRA_SENDER = "sender"
        private const val EXTRA_PREFIX = "prefix"
        private const val EXTRA_UNLOCK = "requires_unlock"

        fun replyIntent(context: Context, target: ReplyTarget): Intent =
            Intent(context, ReplyReceiver::class.java)
                .setAction(ACTION_REPLY)
                // A data URI per conversation, so each gets its own PendingIntent.
                .setData(conversationUri(target.key))
                .putExtra(EXTRA_WORLD_ID, target.worldId)
                .putExtra(EXTRA_WORLD_NAME, target.worldName)
                .putExtra(EXTRA_SENDER, target.sender)
                .putExtra(EXTRA_PREFIX, target.prefix)
                .putExtra(EXTRA_UNLOCK, target.requiresUnlock)

        fun dismissIntent(context: Context, key: ConversationKey): Intent =
            Intent(context, ReplyReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .setData(conversationUri(key))
                .putExtra(EXTRA_WORLD_ID, key.worldId)
                .putExtra(EXTRA_SENDER, key.sender)

        private fun conversationUri(key: ConversationKey): Uri =
            Uri.Builder().scheme("tether").authority("conversation")
                .appendPath(key.worldId.toString())
                .appendPath(key.sender)
                .build()

        private fun Intent.replyTarget(): ReplyTarget? {
            val worldId = getLongExtra(EXTRA_WORLD_ID, -1).takeIf { it >= 0 } ?: return null
            return ReplyTarget(
                worldId = worldId,
                worldName = getStringExtra(EXTRA_WORLD_NAME) ?: return null,
                sender = getStringExtra(EXTRA_SENDER) ?: return null,
                prefix = getStringExtra(EXTRA_PREFIX) ?: return null,
                requiresUnlock = getBooleanExtra(EXTRA_UNLOCK, true),
            )
        }
    }
}
