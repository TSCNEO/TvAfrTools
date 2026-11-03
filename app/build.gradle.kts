plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Signing credentials resolution.
// Priority: environment variables (any CI, any machine) -> keystore.properties
// (local file, git-ignored). Nothing secret is ever committed.
// With no credentials resolved the release APK is built UNSIGNED instead of
// silently signed with a hardcoded key.
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps: Map<String, String> =
    if (keystorePropsFile.isFile) {
        keystorePropsFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val sep = line.indexOf('=')
                if (sep <= 0) null else line.substring(0, sep).trim() to line.substring(sep + 1).trim()
            }
            .toMap()
    } else {
        emptyMap()
    }

fun credential(envName: String, propName: String): String? =
    System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }
        ?: keystoreProps[propName]?.trim()?.takeIf { it.isNotEmpty() }

val ksPath = credential("KEYSTORE_FILE", "storeFile")
val ksPass = credential("KEYSTORE_PASSWORD", "storePassword")
val ksAlias = credential("KEY_ALIAS", "keyAlias")
val ksKeyPass = credential("KEY_PASSWORD", "keyPassword")
val ksFile = ksPath?.let { file(it) }?.takeIf { it.isFile }

val releaseSigningReady = ksFile != null && ksPass != null && ksAlias != null && ksKeyPass != null

if (releaseSigningReady) {
    println("[signing] Release signing enabled (alias=${ksAlias}, keystore=${ksFile?.name}).")
} else {
    println("[signing] No credentials resolved — release APK will be UNSIGNED.")
    println("[signing] Provide KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD or keystore.properties.")
}

android {
    namespace = "dev.custom.tvafrtools"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.custom.tvafrtools"
        minSdk = 28
        targetSdk = 34
        versionCode = 53
        versionName = "1.4.7"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            buildConfigField("int", "DAEMON_PORT", "15099")
            buildConfigField("int", "WEB_PORT", "8099")
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".alpha"
            versionNameSuffix = ".debug"
            buildConfigField("int", "DAEMON_PORT", "15100")
            buildConfigField("int", "WEB_PORT", "8100")
            isMinifyEnabled = false
            // The alpha has its own package and can coexist with the stable app.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
