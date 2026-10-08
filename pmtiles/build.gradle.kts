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
  systemProperty("curveon.online", System.getProperty("curveon.online") ?: "false")
  systemProperty("curveon.data", "${rootDir}/../Curveon-data")
  systemProperty("curveon.catalog", System.getProperty("curveon.catalog") ?: "false")
  systemProperty("curveon.root", "$rootDir")
  maxHeapSize = "6g"
  testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
