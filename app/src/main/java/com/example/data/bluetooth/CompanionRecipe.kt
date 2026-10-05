package com.example.data.bluetooth

object CompanionRecipe {

    val PROTOCOL_UUID = "9a8f4c20-7b21-4f4b-8874-884d59a5db11"

    val FULL_RECIPE_MARKDOWN = """
# VibeNav Phone Companion App Recipe
## Architectural Blueprint for Android Phone

This guide provides the complete implementation details for the mobile phone companion app that bridges the motorcycle intercom, media player, and notifications to the tablet.

---

### 1. Required Android Permissions (Phone Manifest)
```xml
<manifest ...>
    <!-- Bluetooth Classic RFCOMM communication -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
    
    <!-- Contacts access for native contact photos & names -->
    <uses-permission android:name="android.permission.READ_CONTACTS" />
    
    <!-- Phone Call state -->
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
    <uses-permission android:name="android.permission.CALL_PHONE" />
    
    <!-- Foreground Service for background Bluetooth stability -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
</manifest>
```

---

### 2. Notification Mirroring (NotificationListenerService)
Create a class extending `android.service.notification.NotificationListenerService`:
```kotlin
class VibeCompanionNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        // Filter relevant motorcycle apps: WhatsApp, SMS, Telegram, Phone
        if (packageName in listOf("com.whatsapp", "com.google.android.apps.messaging", "org.telegram.messenger")) {
            val extras = sbn.notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: "Unknown"
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val largeIcon = extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
            
            // Send JSON packet via Bluetooth to VibeNav Tablet:
            // { "type": "NOTIFICATION", "app": "WhatsApp", "sender": title, "message": text, "avatarBase64": ... }
            BluetoothBridgeServer.sendNotification(title, text, packageName, largeIcon)
        }
    }
}
```

---

### 3. Music Mirroring & Control (MediaSessionManager)
Hook into system media sessions on the phone to control Spotify, Apple Music, YouTube Music:
```kotlin
val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
val component = ComponentName(context, VibeCompanionNotificationService::class.java)

mediaSessionManager.addOnActiveSessionsChangedListener({ controllers ->
    val activeController = controllers?.firstOrNull() ?: return@addOnActiveSessionsChangedListener
    activeController.registerCallback(object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            
            BluetoothBridgeServer.sendMusicMetadata(title, artist, album, art)
        }
        
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val isPlaying = state?.state == PlaybackState.STATE_PLAYING
            BluetoothBridgeServer.sendPlaybackState(isPlaying, state?.position ?: 0L)
        }
    })
}, component)
```

---

### 4. Handling Commands from Tablet (Motorcycle Controls)
When the motorcycle rider taps Next/Previous/Play/Pause on the tablet dashboard:
```kotlin
fun handleTabletCommand(command: String) {
    when (command) {
        "CMD_MEDIA_PLAY" -> activeController?.transportControls?.play()
        "CMD_MEDIA_PAUSE" -> activeController?.transportControls?.pause()
        "CMD_MEDIA_NEXT" -> activeController?.transportControls?.skipToNext()
        "CMD_MEDIA_PREV" -> activeController?.transportControls?.skipToPrevious()
        "CMD_REPLY_SMS" -> sendSmsQuickReply(phoneNumber, "Riding motorcycle, talk soon!")
    }
}
```

---

### 5. Bluetooth RFCOMM Server Socket
The phone runs a `BluetoothServerSocket`:
```kotlin
val uuid = UUID.fromString("$PROTOCOL_UUID")
val serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("VibeNavBridge", uuid)
val socket = serverSocket.accept() // Waits for tablet to connect
```
    """.trimIndent()
}
