package com.curveon.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon

/**
 * Test scenarios: the phone posts *real* notifications that look like a chat message or an
 * incoming call. The companion's listener picks them up exactly like WhatsApp/SMS/phone ones,
 * so the whole chain is exercised: phone → tablet → Reply / Answer / Decline → back to the phone.
 */
object TestScenarios {

    const val EXTRA_TEST = "com.curveon.companion.TEST"
    private const val CHANNEL = "test"
    private const val KEY_REPLY = "reply"
    private const val ACTION_REPLY = "com.curveon.companion.TEST_REPLY"
    private const val ACTION_CALL = "com.curveon.companion.TEST_CALL"
    private const val EXTRA_ID = "id"
    private const val EXTRA_CHOICE = "choice"

    private var nextId = 100

    private val samples = listOf(
        "Kari Nordmann" to "Er på Dombås om 20 min, møtes på Circle K? ☕",
        "Ola Hansen" to "Hvor er dere nå? Vi venter ved fergekaia.",
        "MC-klubben" to "Søndagsturen går fra Lillehammer kl 10. Hvem blir med?",
        "Mamma" to "Kjør forsiktig! Ring når du er fremme ❤️"
    )

    fun postMessage(context: Context, index: Int = nextId) {
        val (sender, text) = samples[index % samples.size]
        val id = nextId++
        val nm = channel(context)
        val person = Person.Builder().setName(sender).setIcon(Icon.createWithBitmap(avatar(sender))).build()
        val me = Person.Builder().setName("Me").build()
        val replyIntent = PendingIntent.getBroadcast(
            context, id, Intent(context, TestActionReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val reply = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_companion), "Reply", replyIntent
        ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()).build()

        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_companion)
            .setStyle(Notification.MessagingStyle(me).addMessage(text, System.currentTimeMillis(), person))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(reply)
            .setAutoCancel(true)
            .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_TEST, true) })
            .build()
        nm.notify(id, n)
        CompanionHub.update { it.copy(lastEvent = "Test message from $sender sent") }
    }

    /**
     * Rings from a real phone-book contact ("Pappa" if there is one, otherwise a favourite) without
     * attaching a picture, so the companion has to find the photo in the phone book itself.
     */
    fun postCall(context: Context) {
        val contact = findContact(context)
        val caller = contact ?: "Ola Hansen"
        val id = nextId++
        val nm = channel(context)
        fun action(title: String, choice: String) = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_companion), title,
            PendingIntent.getBroadcast(
                context, id * 10 + choice.length,
                Intent(context, TestActionReceiver::class.java).setAction(ACTION_CALL).putExtra(EXTRA_ID, id).putExtra(EXTRA_CHOICE, choice),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_companion)
            .setContentTitle(caller)
            .setContentText("Incoming call")
            .apply { if (contact == null) setLargeIcon(avatar(caller)) }
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .addAction(action("Answer", "answered"))
            .addAction(action("Decline", "declined"))
            .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_TEST, true) })
            .build()
        nm.notify(id, n)
        CompanionHub.update {
            it.copy(lastEvent = "Test call from $caller ringing" + if (contact == null) " (no \"Pappa\" or favourite in contacts)" else "")
        }
    }

    private fun findContact(context: Context): String? {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val cr = context.contentResolver
        val uri = android.provider.ContactsContract.Contacts.CONTENT_URI
        val name = android.provider.ContactsContract.Contacts.DISPLAY_NAME
        fun first(selection: String, args: Array<String>?) =
            cr.query(uri, arrayOf(name), selection, args, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return first("$name = ? COLLATE NOCASE", arrayOf("Pappa"))
            ?: first("${android.provider.ContactsContract.Contacts.STARRED} = 1 AND ${android.provider.ContactsContract.Contacts.PHOTO_URI} IS NOT NULL", null)
    }

    fun clear(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.notification.extras.getBoolean(EXTRA_TEST) }.forEach { nm.cancel(it.id) }
        CompanionHub.update { it.copy(lastEvent = "Test notifications cleared") }
    }

    private fun channel(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Test messages", NotificationManager.IMPORTANCE_DEFAULT))
        return nm
    }

    /** Coloured circle with initials, standing in for a contact photo. */
    private fun avatar(name: String): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val colors = intArrayOf(0xFF7E57C2.toInt(), 0xFF26A69A.toInt(), 0xFFEF6C00.toInt(), 0xFF5C6BC0.toInt())
        c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[Math.abs(name.hashCode()) % colors.size] })
        val initials = name.split(' ').take(2).joinToString("") { it.take(1) }.uppercase()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 52f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        c.drawText(initials, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    /** Receives what the tablet sent back, and shows it on the phone's status screen. */
    class TestActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_ID, 0)
            val nm = context.getSystemService(NotificationManager::class.java)
            when (intent.action) {
                ACTION_REPLY -> {
                    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)
                    CompanionHub.update { it.copy(lastEvent = "✓ Reply received: \"$text\"") }
                }
                ACTION_CALL -> {
                    val choice = intent.getStringExtra(EXTRA_CHOICE)
                    CompanionHub.update { it.copy(lastEvent = "✓ Test call $choice from the tablet") }
                }
            }
            nm.cancel(id)
        }
    }
}
