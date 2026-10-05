// Offline routing on top of BRouter. Plain JVM so it can be unit tested on the desktop
// against real Norway segment files.
plugins { alias(libs.plugins.kotlin.jvm) }

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }

dependencies {
  // BRouter 1.7.10 routing-only jar (MIT), from https://github.com/abrensch/brouter/releases
  api(files("libs/brouter-1.7.10-ro.jar"))
  testImplementation(libs.junit)
}

tasks.test {
  // Integration tests read real segments from here when present, and are skipped otherwise.
  systemProperty("vibenav.segments", System.getProperty("vibenav.segments")
    ?: "${rootDir}/../VibeNav-data/brouter/segments4")
  testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
