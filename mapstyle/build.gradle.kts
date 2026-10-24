// The map's style as Curveon draws it (MapStyle), plain JSON in and out: used by the app and by the web planner,
// so both draw the same map.
plugins { alias(libs.plugins.kotlin.jvm) }

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }

dependencies {
  // org.json is part of Android; the web planner and the tests bring their own.
  compileOnly(libs.json)
  testImplementation(libs.json)
  testImplementation(libs.junit)
}

tasks.test { systemProperty("curveon.root", rootDir.path) }
