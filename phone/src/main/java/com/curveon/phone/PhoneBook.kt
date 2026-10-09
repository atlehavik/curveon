package com.curveon.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import android.util.Log
import com.curveon.protocol.Message
import java.util.concurrent.Executors

/**
 * The phone app on the tablet: contacts, favourites (starred contacts), the call log and dialing.
 * Work runs on its own thread so a big phone book doesn't hold up the Bluetooth reader.
 */
class PhoneBook(private val context: Context) {

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "curveon-phonebook").apply { isDaemon = true } }

    fun shutdown() = worker.shutdownNow()

    fun sendPhoneBook() = worker.execute {
        runCatching { PhoneHub.broadcast(contacts()) }.onFailure { Log.w(TAG, "Contacts", it) }
        runCatching { PhoneHub.broadcast(recentCalls()) }.onFailure { Log.w(TAG, "Call log", it) }
    }

    fun sendPhotos(keys: List<String>) = worker.execute {
        // Small batches: photos show up as they come instead of all at once.
        keys.distinct().chunked(8).forEach { batch ->
            PhoneHub.broadcast(Message.ContactPhotos(batch.map { Message.Photo(it, runCatching { photo(it) }.getOrNull()) }))
        }
    }

    @SuppressLint("MissingPermission") // checked
    fun placeCall(number: String) {
        if (!has(Manifest.permission.CALL_PHONE)) {
            PhoneHub.broadcast(Message.PhoneError("Allow Curveon to make calls on the phone"))
            return
        }
        runCatching {
            context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
            PhoneHub.update { it.copy(lastEvent = "Calling $number") }
        }.onFailure {
            Log.w(TAG, "Call failed", it)
            PhoneHub.broadcast(Message.PhoneError("Couldn't call $number"))
        }
    }

    // ---------------------------------------------------------------- contacts

    private fun contacts(): Message.Contacts {
        if (!has(Manifest.permission.READ_CONTACTS)) return Message.Contacts(emptyList(), granted = false)
        data class Row(val name: String, val starred: Boolean, val photoId: Long, val numbers: MutableList<Message.PhoneNumber>)
        val byId = LinkedHashMap<Long, Row>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.STARRED, Phone.PHOTO_ID, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.IS_SUPER_PRIMARY),
            null, null, "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC, ${Phone.IS_SUPER_PRIMARY} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(4)?.trim().orEmpty()
                if (number.isEmpty()) continue
                val row = byId.getOrPut(c.getLong(0)) {
                    Row(c.getString(1) ?: number, c.getInt(2) == 1, c.getLong(3), mutableListOf())
                }
                // The same number is often stored twice (e.g. "+47 912…" and "912…").
                if (row.numbers.none { digits(it.number).takeLast(8) == digits(number).takeLast(8) }) {
                    row.numbers += Message.PhoneNumber(number, typeLabel(c.getInt(5), c.getString(6)))
                }
            }
        }
        return Message.Contacts(byId.map { (id, r) -> Message.Contact(id, r.name, r.starred, photoKey(id, r.photoId), r.numbers) })
    }

    // ---------------------------------------------------------------- call log

    private fun recentCalls(): Message.RecentCalls {
        if (!has(Manifest.permission.READ_CALL_LOG)) return Message.RecentCalls(emptyList(), granted = false)
        val calls = mutableListOf<Message.RecentCall>()
        val lookups = HashMap<String, Triple<String, String, String?>?>() // number -> (name, label, photo key)
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "200").build(),
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
            null, null, "${CallLog.Calls.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext() && calls.size < MAX_RECENTS) {
                val number = c.getString(0)?.trim().orEmpty()
                if (number.isEmpty()) continue // private / withheld numbers can't be called back
                val kind = when (c.getInt(1)) {
                    CallLog.Calls.OUTGOING_TYPE -> Message.CallKind.OUTGOING
                    CallLog.Calls.MISSED_TYPE -> Message.CallKind.MISSED
                    CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> Message.CallKind.REJECTED
                    else -> Message.CallKind.INCOMING
                }
                val time = c.getLong(2)
                // Fold "3 missed calls from Kari" into one row, like the phone's own dialer.
                val last = calls.lastOrNull()
                if (last != null && last.kind == kind && digits(last.number) == digits(number)) {
                    calls[calls.lastIndex] = last.copy(count = last.count + 1)
                    continue
                }
                val contact = lookups.getOrPut(number) { lookup(number) }
                calls += Message.RecentCall(number, contact?.first, contact?.second.orEmpty(), contact?.third, kind, time)
            }
        }
        return Message.RecentCalls(calls)
    }

    private fun lookup(number: String): Triple<String, String, String?>? {
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_ID,
                ContactsContract.PhoneLookup.TYPE, ContactsContract.PhoneLookup.LABEL),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) null
            else Triple(c.getString(1) ?: number, typeLabel(c.getInt(3), c.getString(4)), photoKey(c.getLong(0), c.getLong(2)))
        }
    }

    // ---------------------------------------------------------------- photos

    /** "contactId:photoId": the contact to read from, and a part that changes with the photo. */
    private fun photoKey(contactId: Long, photoId: Long) = if (photoId > 0) "$contactId:$photoId" else null

    private fun photo(key: String): String? {
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        val contactId = key.substringBefore(':').toLongOrNull() ?: return null
        val contact = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        // The full-size photo when there is one (favourite tiles are big); the 96 px thumbnail otherwise.
        val bitmap = ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, contact, true)
            ?.use { BitmapFactory.decodeStream(it) } ?: return null
        return PhoneHub.encodeJpeg(bitmap, PHOTO_PX, 82)
    }

    // ---------------------------------------------------------------- helpers

    private fun typeLabel(type: Int, custom: String?): String =
        Phone.getTypeLabel(context.resources, type, custom ?: "").toString()

    private fun digits(number: String) = number.filter { it.isDigit() }

    private fun has(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "PhoneBook"
        private const val MAX_RECENTS = 40
        private const val PHOTO_PX = 256
    }
}
