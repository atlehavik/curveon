plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.curveon"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.curveon.app"
    minSdk = 29
    targetSdk = 36
    versionCode = 9
    versionName = "1.2.4"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }

  buildTypes {
    // DEV: developer aids (demo data, fake OBD values) that public releases leave out. The demo
    // ride is in every build.
    debug {
      buildConfigField("boolean", "DEV", "true")
      buildConfigField("boolean", "SELF_UPDATE", "true")
    }
    // Like release (not debuggable, so ART runs routing at full speed) but signed with the debug
    // key, so it installs over a debug build and keeps the app's data. For the bike tablet.
    create("ride") {
      initWith(getByName("debug"))
      isDebuggable = false
      matchingFallbacks += "release"
      buildConfigField("boolean", "DEV", "true")
      buildConfigField("boolean", "SELF_UPDATE", "true")
    }
    release {
      buildConfigField("boolean", "DEV", "false")
      // Updates itself from the GitHub releases page (sideloaded builds only).
      buildConfigField("boolean", "SELF_UPDATE", "true")
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    // The Google Play bundle (`:app:bundlePlay`): release, but Play updates it, so no installer
    // permission and no update check (src/play/AndroidManifest.xml drops REQUEST_INSTALL_PACKAGES).
    create("play") {
      initWith(getByName("release"))
      matchingFallbacks += "release"
      buildConfigField("boolean", "SELF_UPDATE", "false")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  // Lists English and Norwegian under the system's App languages too.
  androidResources { generateLocaleConfig = true }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

dependencies {
  implementation(project(":routing"))
  implementation(project(":protocol"))
  implementation(project(":phone"))
  implementation(project(":groups"))
  implementation(project(":pmtiles"))
  implementation(project(":mapstyle"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // Scanning a group invite's QR code, with the camera permission and the CameraX libraries.
  implementation(project(":qrscan"))
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  implementation(libs.maplibre.android)
  // Cutting the rider out of a profile picture for its styles: the model comes with Google Play services.
  implementation(libs.mlkit.subject.segmentation)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}

// Each database version's schema is kept in the repo, so a migration can be tested against what riders really have.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
