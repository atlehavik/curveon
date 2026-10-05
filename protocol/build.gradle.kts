// Messages exchanged between the tablet and the phone companion over Bluetooth RFCOMM.
// org.json is part of Android; it is only added explicitly for desktop unit tests.
plugins { alias(libs.plugins.kotlin.jvm) }

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }

dependencies {
  compileOnly(libs.json)
  testImplementation(libs.json)
  testImplementation(libs.junit)
}
