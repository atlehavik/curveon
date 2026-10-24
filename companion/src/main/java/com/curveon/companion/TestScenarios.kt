package com.curveon.companion

import com.curveon.phone.*
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

    const val EXTRA_TEST = PhoneHub.EXTRA_TEST
    private const val CHANNEL = "test"
    private const val KEY_REPLY = "reply"
    private const val ACTION_REPLY = "com.curveon.companion.TEST_REPLY"
    private const val ACTION_CALL = "com.curveon.companion.TEST_CALL"
    private const val EXTRA_ID = "id"
    private const val EXTRA_CHOICE = "choice"
    private const val EXTRA_CALLER = "caller"

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
        PhoneHub.update { it.copy(lastEvent = "Test message from $sender sent") }
    }

    /**
     * Rings from a real phone-book contact ("Pappa" if there is one, otherwise a favourite) without
     * attaching a picture, so the companion has to find the photo in the phone book itself.
     * Answering it from the tablet turns the same notification into an ongoing call, like a real dialer.
     */
    fun postCall(context: Context) {
        val contact = findContact(context)
        val caller = contact ?: "Ola Hansen"
        val id = nextId++
        val nm = channel(context)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_companion)
            .setContentTitle(caller)
            .setContentText("Incoming call")
            .apply { if (contact == null) setLargeIcon(avatar(caller)) }
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .addAction(callAction(context, id, caller, 0, "Answer", "answered"))
            .addAction(callAction(context, id, caller, 1, "Decline", "declined"))
            .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_TEST, true) })
            .build()
        nm.notify(id, n)
        PhoneHub.update {
            it.copy(lastEvent = "Test call from $caller ringing" + if (contact == null) " (no \"Pappa\" or favourite in contacts)" else "")
        }
    }

    /**
     * The answered call, like the dialer shows it: a running call timer and "Speaker" / "Hang up".
     * The phone's listener sees the timer and reports the call as ongoing to the tablet.
     */
    private fun postOngoing(context: Context, id: Int, caller: String) {
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_companion)
            .setContentTitle(caller)
            .setContentText("Ongoing call")
            .apply { if (findContact(context) != caller) setLargeIcon(avatar(caller)) }
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .addAction(callAction(context, id, caller, 0, "Speaker", "speaker"))
            .addAction(callAction(context, id, caller, 1, "Hang up", "hungup"))
            .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_TEST, true) })
            .build()
        channel(context).notify(id, n)
    }

    /** One of a test call's buttons; [slot] keeps the pending intents apart (extras don't count when they are compared). */
    private fun callAction(context: Context, id: Int, caller: String, slot: Int, title: String, choice: String) = Notification.Action.Builder(
        Icon.createWithResource(context, R.drawable.ic_companion), title,
        PendingIntent.getBroadcast(
            context, id * 10 + slot,
            Intent(context, TestActionReceiver::class.java).setAction(ACTION_CALL)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_CHOICE, choice).putExtra(EXTRA_CALLER, caller),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    ).build()

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
        PhoneHub.update { it.copy(lastEvent = "Test notifications cleared") }
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
                    PhoneHub.update { it.copy(lastEvent = "✓ Reply received: \"$text\"") }
                }
                ACTION_CALL -> {
                    val choice = intent.getStringExtra(EXTRA_CHOICE)
                    PhoneHub.update { it.copy(lastEvent = "✓ Test call $choice from the tablet") }
                    when (choice) {
                        // Answered: the same call carries on as an ongoing one. Speaker doesn't end it.
                        "answered" -> { postOngoing(context, id, intent.getStringExtra(EXTRA_CALLER) ?: "Ola Hansen"); return }
                        "speaker" -> return
                    }
                }
            }
            nm.cancel(id)
        }
    }
}
