import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
  keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}
fun releaseSigningValue(property: String, environmentVariable: String): String? =
  keystoreProperties.getProperty(property) ?: System.getenv(environmentVariable)

android {
  namespace = "app.hoot"
  compileSdk = 36
  defaultConfig {
    applicationId = "app.hoot"
    minSdk = 26
    targetSdk = 36
    versionCode = 2
    versionName = "1.0.1"
  }

  signingConfigs {
    create("release") {
      val signingStore = releaseSigningValue("release.storeFile", "HOOT_ANDROID_KEYSTORE_PATH")
      val signingStorePassword = releaseSigningValue("release.storePassword", "HOOT_ANDROID_KEYSTORE_PASSWORD")
      val signingKeyAlias = releaseSigningValue("release.keyAlias", "HOOT_ANDROID_KEY_ALIAS")
      val signingKeyPassword = releaseSigningValue("release.keyPassword", "HOOT_ANDROID_KEY_PASSWORD")
      if (signingStore != null && signingStorePassword != null && signingKeyAlias != null && signingKeyPassword != null) {
        storeFile = file(signingStore)
        storePassword = signingStorePassword
        keyAlias = signingKeyAlias
        keyPassword = signingKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation("androidx.exifinterface:exifinterface:1.3.7")
  implementation("androidx.documentfile:documentfile:1.0.1")
  implementation("com.caverock:androidsvg-aar:1.4")
  implementation("com.squareup:gifencoder:0.10.1")
  implementation("com.tom-roush:pdfbox-android:2.0.27.0")

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
