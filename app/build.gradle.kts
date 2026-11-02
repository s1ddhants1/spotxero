import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val versionMajor = 1
val versionMinor = 1
val versionPatch = 0

val appVersionCode = versionMajor * 100 + versionMinor * 10 + versionPatch
val appVersionName = "$versionMajor.$versionMinor.$versionPatch"

android {
    namespace = "app.spotxero"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.spotxero"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        val keystoreProperties = Properties().apply {
            val propFile = rootProject.file("keystore.properties").takeIf { it.isFile }
                ?: rootProject.file("signing.properties").takeIf { it.isFile }
            propFile?.inputStream()?.use { load(it) }
        }

        fun getSigningProperty(key: String): String? {
            return keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }
                ?: (project.findProperty(key) as? String)?.takeIf { it.isNotBlank() }
                ?: System.getenv(key)?.takeIf { it.isNotBlank() }
        }

        val keystorePath = getSigningProperty("KEYSTORE_FILE")
            ?: getSigningProperty("KEYSTORE_PATH")
            ?: getSigningProperty("SPOTXERO_KEYSTORE_PATH")
            ?: getSigningProperty("spotxeroKeystoreFile")
            ?: getSigningProperty("spotxero.keystore.file")

        val keystoreFile = when {
            !keystorePath.isNullOrBlank() -> rootProject.file(keystorePath)
            rootProject.file("spotxero.keystore").exists() -> rootProject.file("spotxero.keystore")
            project.file("spotxero.keystore").exists() -> project.file("spotxero.keystore")
            else -> {
                val base64Key = System.getenv("KEYSTORE") ?: System.getenv("SPOTXERO_KEYSTORE_BASE64")
                if (!base64Key.isNullOrBlank()) {
                    val decodedKeystore = rootProject.layout.buildDirectory.file("keystore/spotxero.keystore").get().asFile
                    decodedKeystore.parentFile.mkdirs()
                    decodedKeystore.writeBytes(Base64.getDecoder().decode(base64Key.trim()))
                    decodedKeystore
                } else {
                    null
                }
            }
        }

        val keystorePassword = getSigningProperty("KEYSTORE_PASSWORD")
            ?: getSigningProperty("SPOTXERO_KEYSTORE_PASSWORD")
            ?: getSigningProperty("spotxeroKeystorePassword")
            ?: getSigningProperty("spotxero.keystore.password")

        val keyAliasName = getSigningProperty("KEY_ALIAS")
            ?: getSigningProperty("SPOTXERO_KEY_ALIAS")
            ?: getSigningProperty("spotxeroKeyAlias")
            ?: getSigningProperty("spotxero.key.alias")
            ?: "spotxero"

        val keyPasswordVal = getSigningProperty("KEY_PASSWORD")
            ?: getSigningProperty("SPOTXERO_KEY_PASSWORD")
            ?: getSigningProperty("spotxeroKeyPassword")
            ?: getSigningProperty("spotxero.key.password")
            ?: keystorePassword

        if (keystoreFile != null && keystoreFile.exists() && !keystorePassword.isNullOrBlank()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordVal
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
            getByName("debug").apply {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordVal
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.animation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coroutines.android)
}
