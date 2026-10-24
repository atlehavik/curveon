// The web planner's routing service (relay.curveon.no/plan): the app's own :routing over HTTP, so a trip planned on a
// PC takes the same roads as one planned in the app. Plain JVM, the JDK's HTTP server, no other dependencies.
// `./gradlew :webplanner:serverJar` makes the one jar that runs on the VPS (tools/deploy-webplanner.sh).
plugins { alias(libs.plugins.kotlin.jvm) }

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
  implementation(project(":routing"))
  implementation(libs.json)
  testImplementation(libs.junit)
}

tasks.register<Jar>("serverJar") {
  archiveFileName.set("webplanner-server.jar")
  manifest { attributes("Main-Class" to "com.curveon.webplanner.MainKt") }
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  from(sourceSets.main.get().output)
  dependsOn(configurations.runtimeClasspath)
  from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
  exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.test {
  systemProperty("curveon.segments", System.getProperty("curveon.segments") ?: "${rootDir}/../Curveon-data/brouter/segments4")
}
