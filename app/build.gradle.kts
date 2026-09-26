plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.wagateway.qnzr"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      // The Go/whatsmeow gateway (libgojni.so) is only bound for these ABIs — see the
      // `gomobile bind -target=android/arm64,android/amd64,android/arm` step in
      // .github/workflows/build.yml. Declaring them here stops Play/devices from
      // installing the app on an ABI that has no libgojni.so, which would otherwise
      // fail at startup with UnsatisfiedLinkError.
      //
      // Adding "x86" here would additionally require adding android/386 to the
      // gomobile bind target.
      abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }

    // Release-grade build that CI can produce with no signing secrets at all: R8 + resource
    // shrinking, not debuggable, signed with the debug key so it installs over a debug build.
    // This is the artifact to compare against the debug APK when something "feels slow":
    // `isDebuggable = false` (inherited from release) is what lets ART run the app optimized
    // instead of in debug mode, and R8 removes the unused library code from the DEX.
    //
    // `release` itself keeps minification off for now: R8 runs against keep-rules that cannot
    // be device-tested from this repository's CI, so the signed release path stays untouched
    // until the optimized APK has been tried on a real device.
    create("optimized") {
      initWith(getByName("release"))
      signingConfig = signingConfigs.getByName("debugConfig")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      matchingFallbacks += listOf("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
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

// Some unused dependencies are kept commented out below instead of being removed,
// so they can be added back easily when the feature that needs them is built.
dependencies {
  // Native WhatsApp Gateway (Go / whatsmeow, produced by .github/workflows/build.yml)
  implementation(files("libs/wagateway.aar"))
  implementation("com.google.zxing:core:3.5.3")

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // Scheduler reliability: a periodic worker wakes the agent's scheduled tasks even
  // when the process was killed by the system (the in-app ticker handles the rest).
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // HTTP client used by the OpenAI-compatible model provider
  implementation(libs.okhttp)

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)

  "ksp"(libs.androidx.room.compiler)
}
