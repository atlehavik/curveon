// Group-ride protocol: invite, keys, encrypted frames, rekey. No Android dependencies, so it is unit
// tested on the desktop, used by :companion and by Curveon's "This device" mode, and could become
// Kotlin Multiplatform. Wire format: docs/groups-protocol.md.
plugins { alias(libs.plugins.kotlin.jvm) }

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }

dependencies {
  // X25519, Ed25519 and HKDF: the platform has them only from API 33, minSdk is 29.
  implementation(libs.tink.android)
  // QR codes drawn for invites and read from the camera. Pure Java (Apache 2.0), no Play services.
  implementation(libs.zxing.core)
  // org.json is part of Android; it is only added explicitly for desktop unit tests.
  compileOnly(libs.json)
  testImplementation(libs.json)
  testImplementation(libs.junit)
}
