import java.util.Base64

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

  defaultConfig {
    applicationId = "com.aistudio.v2raydan.kqtxwp"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
  implementation(libs.converter.moshi)
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

// ─────────────────────────────────────────────────────────────
// Task 1: دانلود Xray core binary
// ─────────────────────────────────────────────────────────────
tasks.register("downloadXrayCore") {
    group = "build setup"
    description = "Downloads Xray core arm64 binary and places it in jniLibs"

    val xrayUrl = "https://github.com/XTLS/Xray-core/releases/download/v1.8.24/Xray-android-arm64-v8a.zip"
    val outputDir = file("src/main/jniLibs/arm64-v8a")
    val jniLibFile = file("src/main/jniLibs/arm64-v8a/libxray.so")

    inputs.property("xrayUrl", xrayUrl)
    outputs.file(jniLibFile)

    doLast {
        if (jniLibFile.exists() && jniLibFile.length() > 1000) {
            println("[Xray] Already exists (${jniLibFile.length()} bytes). Skipping.")
            return@doLast
        }

        outputDir.mkdirs()
        val tempZip = file("build/tmp/Xray-android-arm64-v8a.zip")
        tempZip.parentFile.mkdirs()

        println("[Xray] Downloading from $xrayUrl ...")
        try {
            val connection = java.net.URI(xrayUrl).toURL().openConnection()
            connection.connect()
            connection.getInputStream().use { ins ->
                tempZip.outputStream().use { out -> ins.copyTo(out) }
            }
            println("[Xray] Downloaded (${tempZip.length()} bytes). Extracting...")

            project.zipTree(tempZip).visit {
                if (!isDirectory && name == "xray") {
                    file.copyTo(jniLibFile, overwrite = true)
                    println("[Xray] ✓ Extracted to ${jniLibFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            println("[Xray] ERROR: ${e.message}")
            throw e
        } finally {
            if (tempZip.exists()) tempZip.delete()
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Task 2: دانلود tun2socks binary
// ─────────────────────────────────────────────────────────────
tasks.register("downloadTun2socks") {
    group = "build setup"
    description = "Downloads tun2socks arm64 binary and places it in assets/"

    val tun2socksVersion = "v2.5.2"
    val tun2socksUrl =
        "https://github.com/xjasonlyu/tun2socks/releases/download/" +
        "$tun2socksVersion/tun2socks-linux-android-arm64.zip"
    val assetsDir = file("src/main/assets")
    val destFile  = file("src/main/assets/tun2socks")

    inputs.property("tun2socksUrl", tun2socksUrl)
    outputs.file(destFile)

    doLast {
        if (destFile.exists() && destFile.length() > 1000) {
            println("[tun2socks] Already exists (${destFile.length()} bytes). Skipping.")
            return@doLast
        }

        assetsDir.mkdirs()
        val tempZip = file("build/tmp/tun2socks.zip")
        tempZip.parentFile.mkdirs()

        println("[tun2socks] Downloading from $tun2socksUrl ...")
        try {
            val connection = java.net.URI(tun2socksUrl).toURL().openConnection()
            connection.connect()
            connection.getInputStream().use { ins ->
                tempZip.outputStream().use { out -> ins.copyTo(out) }
            }
            println("[tun2socks] Downloaded (${tempZip.length()} bytes). Extracting...")

            var found = false
            project.zipTree(tempZip).visit {
                if (!isDirectory && name == "tun2socks") {
                    file.copyTo(destFile, overwrite = true)
                    found = true
                    println("[tun2socks] ✓ Extracted: ${destFile.absolutePath}")
                }
            }

            if (!found) {
                throw GradleException("[tun2socks] Binary not found in ZIP! URL: $tun2socksUrl")
            }
            println("[tun2socks] ✓ Ready (${destFile.length()} bytes)")

        } catch (e: Exception) {
            if (destFile.exists()) destFile.delete()
            throw e
        } finally {
            if (tempZip.exists()) tempZip.delete()
        }
    }
}

// ─────────────────────────────────────────────────────────────
// هر دو task قبل از preBuild اجرا میشن
// ─────────────────────────────────────────────────────────────
tasks.named("preBuild") {
    dependsOn("downloadXrayCore")
    dependsOn("downloadTun2socks")
}
