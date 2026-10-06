// Cuts a region out of a remote PMTiles archive (e.g. the Protomaps daily world build) with HTTP
// range requests. Plain JVM so it can be tested against the real server from the desktop.
plugins { alias(libs.plugins.kotlin.jvm) }

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }

dependencies {
  implementation(libs.okhttp)
  testImplementation(libs.junit)
}

tasks.test {
  systemProperty("vibenav.online", System.getProperty("vibenav.online") ?: "false")
  systemProperty("vibenav.data", "${rootDir}/../VibeNav-data")
  testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
