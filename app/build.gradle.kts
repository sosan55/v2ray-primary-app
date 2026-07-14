import java.util.Base64
import java.net.URL

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  ndkVersion = "27.0.12077973"

  defaultConfig {
    applicationId = "com.aistudio.v2raydan.kqtxwp"
    minSdk = 24
    targetSdk = 36
    versionCode = 3
    versionName = "1.2"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      abiFilters += "arm64-v8a"
    }

    externalNativeBuild {
      cmake {
        cppFlags += ""
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  signingConfigs {
    create("release") {
      val base64Keystore = System.getenv("KEYSTORE_BASE64")
      val keystorePathEnv = System.getenv("KEYSTORE_PATH")
      val keystorePath = if (!base64Keystore.isNullOrBlank()) {
        val tempKeystore = file("${rootDir}/build/release.keystore")
        tempKeystore.parentFile.mkdirs()
        tempKeystore.writeBytes(Base64.getMimeDecoder().decode(base64Keystore.trim()))
        tempKeystore.absolutePath
      } else {
        if (!keystorePathEnv.isNullOrBlank()) keystorePathEnv else "${rootDir}/my-upload-key.jks"
      }
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      val keystore = file("${rootDir}/debug.keystore")
      if (keystore.exists()) {
        storeFile = keystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      } else {
        try {
          val builtInDebug = signingConfigs.getByName("debug")
          storeFile = builtInDebug.storeFile
          storePassword = builtInDebug.storePassword
          keyAlias = builtInDebug.keyAlias
          keyPassword = builtInDebug.keyPassword
        } catch (_: Exception) {
          storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
          storePassword = "android"
          keyAlias = "androiddebugkey"
          keyPassword = "android"
        }
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      
      val base64Keystore = System.getenv("KEYSTORE_BASE64")
      val keystorePathEnv = System.getenv("KEYSTORE_PATH")
      val keystorePath = if (!keystorePathEnv.isNullOrBlank()) keystorePathEnv else "${rootDir}/my-upload-key.jks"
      val keystoreFile = file(keystorePath)
      if ((keystoreFile.exists() || !base64Keystore.isNullOrBlank()) && !System.getenv("STORE_PASSWORD").isNullOrBlank()) {
        signingConfig = signingConfigs.getByName("release")
      } else {
        signingConfig = signingConfigs.getByName("debugConfig")
      }
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
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

  packaging {
    jniLibs {
      useLegacyPackaging = true
      keepDebugSymbols.add("**/libxray.so")
      doNotStrip.add("**/libxray.so")
    }
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation("androidx.activity:activity-ktx:1.10.1")
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
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)

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
  "ksp"(libs.moshi.kotlin.codegen)
}

// ==================== Xray Core Download Task ====================

tasks.register("downloadXrayCore") {
    group = "build setup"
    description = "Downloads Xray core and assets"

    // تغییر نسخه به 1.8.23 برای رفع مشکل slice bounds
    val xrayUrl = "https://github.com/XTLS/Xray-core/releases/download/v1.8.23/Xray-android-arm64-v8a.zip"
    
    val jniLibFile = file("src/main/jniLibs/arm64-v8a/libxray.so")
    val geoipFile = file("src/main/assets/geoip.dat")
    val geositeFile = file("src/main/assets/geosite.dat")

    inputs.property("xrayUrl", xrayUrl)
    outputs.files(jniLibFile, geoipFile, geositeFile)

    doLast {
        val needsDownload = true  // Force download برای تست - بعداً می‌تونی برگردونی به شرط قبلی

        if (needsDownload) {
            jniLibFile.parentFile.mkdirs()
            geoipFile.parentFile.mkdirs()
            val tempZip = file("build/tmp/Xray-android-arm64-v8a.zip")
            tempZip.parentFile.mkdirs()
            
            println("Downloading Xray v1.8.23 from $xrayUrl ...")
            try {
                URL(xrayUrl).openStream().use { input ->
                    tempZip.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                println("Download complete. Extracting files...")
                zipTree(tempZip).forEach { extractedFile ->
                    when (extractedFile.name) {
                        "xray" -> {
                            extractedFile.copyTo(jniLibFile, overwrite = true)
                            println("Extracted xray core to $jniLibFile")
                        }
                        "geoip.dat" -> extractedFile.copyTo(geoipFile, overwrite = true)
                        "geosite.dat" -> extractedFile.copyTo(geositeFile, overwrite = true)
                    }
                }
            } catch (e: Exception) {
                println("Error downloading Xray: ${e.message}")
                throw e
            } finally {
                if (tempZip.exists()) tempZip.delete()
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadXrayCore")
}
