// What a phone shares (messages, calls, music, contacts, spoken replies), read on the phone itself.
// Used by the companion app to send it to the tablet, and by Curveon to show it on the same device.
plugins { alias(libs.plugins.android.library) }

android {
  namespace = "com.curveon.phone"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  defaultConfig { minSdk = 29 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  api(project(":protocol"))
  implementation(project(":groups"))
  implementation(libs.okhttp)
  // Draws the invite QR code. Pure Java (Apache 2.0), no Play services.
  implementation(libs.zxing.core)
  implementation(libs.kotlinx.coroutines.android)
}
