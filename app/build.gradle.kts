import java.nio.ByteBuffer
import java.nio.ByteOrder

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.tethr"
    compileSdk = 36
    defaultConfig {
        applicationId = "ai.tethr.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 17
        versionName = "1.16"
    }

    ndkVersion = "28.2.13676358"

    signingConfigs {
        create("release") {
            storeFile = file("upload-keystore.jks")
            storePassword = "tethr1234"
            keyAlias = "upload"
            keyPassword = "tethr1234"
        }
    }


    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
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
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Material Icons Extended — required for Icons.Rounded.* in MainScreen
  implementation("androidx.compose.material:material-icons-extended")
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

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Edge AI NLP (TensorFlow Lite Task Library)
  implementation("org.tensorflow:tensorflow-lite-task-text:0.4.4")
  
  // ML Kit for Intention NLP
  implementation("com.google.mlkit:language-id:17.0.6")
  
  // Google Play Billing
  implementation("com.android.billingclient:billing-ktx:8.0.0")
  
  // DataStore for Preferences
  implementation("androidx.datastore:datastore-preferences:1.1.1")
  
  // Google Play App Update
  implementation("com.google.android.play:app-update:2.1.0")
  implementation("com.google.android.play:app-update-ktx:2.1.0")
}

// Patch all merged native libraries to use 16KB ELF alignment.
// This fixes the Play Console "does not support 16 KB memory page sizes" error
// for prebuilt .so files from dependencies (TensorFlow Lite, ML Kit, etc.)
// that were compiled with 4KB alignment and have no updated versions available.
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        tasks.matching { it.name == "merge${variantName}NativeLibs" }.configureEach {
            doLast {
                val libDir = outputs.files.files.firstOrNull()?.resolve("lib")
                if (libDir != null && libDir.exists()) {
                    val PT_LOAD: Int = 1
                    val TARGET_ALIGN_64: Long = 0x4000L
                    val TARGET_ALIGN_32: Int = 0x4000
                    libDir.walkTopDown().filter { it.extension == "so" }.forEach { soFile ->
                        val bytes = soFile.readBytes()
                        if (bytes.size < 64 || bytes[0] != 0x7F.toByte() || bytes[1] != 0x45.toByte() ||
                            bytes[2] != 0x4C.toByte() || bytes[3] != 0x46.toByte()) return@forEach
                        val eiClass = bytes[4].toInt()
                        var patched = false
                        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        if (eiClass == 2) { // 64-bit
                            val ePhoff = buf.getLong(0x20)
                            val ePhentsize = buf.getShort(0x36).toInt() and 0xFFFF
                            val ePhnum = buf.getShort(0x38).toInt() and 0xFFFF
                            for (i in 0 until ePhnum) {
                                val phOff = (ePhoff + i * ePhentsize).toInt()
                                val pType = buf.getInt(phOff)
                                if (pType == PT_LOAD) {
                                    val pAlignOff = phOff + 48
                                    val pAlign = buf.getLong(pAlignOff)
                                    if (pAlign < TARGET_ALIGN_64) {
                                        buf.putLong(pAlignOff, TARGET_ALIGN_64)
                                        patched = true
                                    }
                                }
                            }
                        } else if (eiClass == 1) { // 32-bit
                            val ePhoff = buf.getInt(0x1C)
                            val ePhentsize = buf.getShort(0x2A).toInt() and 0xFFFF
                            val ePhnum = buf.getShort(0x2C).toInt() and 0xFFFF
                            for (i in 0 until ePhnum) {
                                val phOff = ePhoff + i * ePhentsize
                                val pType = buf.getInt(phOff)
                                if (pType == PT_LOAD) {
                                    val pAlignOff = phOff + 28
                                    val pAlign = buf.getInt(pAlignOff)
                                    if (pAlign < TARGET_ALIGN_32) {
                                        buf.putInt(pAlignOff, TARGET_ALIGN_32)
                                        patched = true
                                    }
                                }
                            }
                        }
                        if (patched) {
                            soFile.writeBytes(bytes)
                            println("16KB-aligned: ${soFile.name}")
                        }
                    }
                }
            }
        }
    }
}
