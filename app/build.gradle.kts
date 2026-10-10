import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// release-please rewrites the literal below on each release; versionCode is
// derived from it so it always moves too. Android decides whether an APK is an
// upgrade by versionCode alone, so a frozen value blocks in-place upgrades even
// when the version name changes.
val appVersionName = "0.3.1" // x-release-please-version

val appVersionCode = appVersionName.substringBefore('-').split('.').let { parts ->
    require(parts.size == 3) { "Expected a three-part version, found '$appVersionName'." }
    val (major, minor, patch) = parts.map { part ->
        val number = part.toIntOrNull()
        requireNotNull(number) { "Version part '$part' in '$appVersionName' is not a number." }
        require(number in 0..99) { "Version part '$part' in '$appVersionName' must be 0-99." }
        number
    }
    major * 10_000 + minor * 100 + patch
}

// Short git revision baked into BuildConfig so two local builds of the same
// version are told apart on the device and in a shared journal (ANDROID-REL-01).
// Falls back to "unknown" without git (a source tarball). versionName and
// versionCode are untouched, so scripts/check-apk-metadata.sh keeps passing.
val gitRevision: String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrDefault("").ifBlank { "unknown" }

android {
    namespace = "com.achappell.hermesrelay"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.achappell.hermesrelay"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Live-relay tests need the tailnet; keep them out of the default run.
        testInstrumentationRunnerArguments["notAnnotation"] =
            "com.achappell.hermesrelay.LiveRelay"
        vectorDrawables {
            useSupportLibrary = true
        }
        buildConfigField("String", "GIT_REVISION", "\"$gitRevision\"")
    }

    // Release signing comes from an external keystore so the APK is installable.
    // CI supplies these via secrets; local builds without them fall back to the
    // debug key so `assembleRelease` keeps working offline.
    // Resolve against the repository root, not the module directory, so a
    // relative path means what the caller expects. A path that was supplied but
    // does not exist is an error: silently falling back to the debug key
    // produces an APK that cannot upgrade a real install, and says nothing.
    val releaseKeystore = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull
        ?.let { path ->
            val candidate = File(path).let { file ->
                if (file.isAbsolute) file else rootProject.file(path)
            }
            require(candidate.exists()) {
                "RELEASE_KEYSTORE_FILE is set to '$path', which resolves to " +
                    "${candidate.absolutePath} and does not exist."
            }
            candidate
        }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // ANDROID-REL-03: R8 shrinking, optimisation and resource shrinking
            // for the sideloaded release APK. Library consumer rules (OkHttp,
            // Okio, CameraX, Compose) are applied automatically; the app's own
            // additions are in proguard-rules.pro. Debug builds stay unminified.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        // Only for GIT_REVISION and DEBUG (ANDROID-REL-01).
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        // Keep emitted Android bytecode compatible with the API 26 floor while
        // the Gradle and Kotlin toolchain runs on the project JDK 21 baseline.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.okhttp)
    // In-app pairing QR scanner (ANDROID-HOME-02). ZXing is pure Java: no
    // native libraries and no Google Play services.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    // Android stubs org.json in local unit tests; supply a real implementation.
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
