import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// A release sets these from its tag (-PappVersion=0.1.0) and repo (-PreleasesRepo=owner/name), as in BookahCalendar.
// A local build never looks for updates (releasesRepo empty).
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "0.1.0"
val releasesRepo = (findProperty("releasesRepo") as String?).orEmpty()

android {
    namespace = "dev.bookah.tasks"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.bookah.tasks"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // 1.2.3 -> 10203: each release installs over the last as long as tags only go up.
        val (major, minor, patch) = appVersion.substringBefore('-').split('.').map { it.toInt() }.plus(listOf(0, 0)).take(3)
        require(minor < 100 && patch < 100) { "appVersion $appVersion: minor and patch must stay under 100" }
        versionCode = major * 10_000 + minor * 100 + patch
        versionName = appVersion
        buildConfigField("String", "RELEASES_REPO", "\"$releasesRepo\"")
        // Every Android phone made in the last years is arm64; emulator test builds on a PC add x86_64 with -PallAbis (never for a release).
        ndk { abiFilters += if (findProperty("allAbis") != null) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Release signing comes from an untracked keystore.properties in this android folder; without it a release build uses the debug key.
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    if (keystore == null && releasesRepo.isNotEmpty()) error("releasesRepo is set but keystore.properties is missing")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // -PseedRelease puts the debug-only data seeder into a LOCAL release-configuration build (R8 full optimisation, built for the emulator),
    // so the real release code path can be tested with generated tasks. It can never be combined with a publishing build.
    if (findProperty("seedRelease") != null) {
        if (releasesRepo.isNotEmpty()) error("seedRelease must never be combined with releasesRepo")
        sourceSets.getByName("release").kotlin.directories.add("src/debug/kotlin")
        sourceSets.getByName("release").manifest.srcFile("src/debug/AndroidManifest.xml")
    }

    buildTypes {
        debug {
            // -PminifyDebug runs the same R8 shrinking as a release on the debug build. The REAL release configuration must still be run
            // before anything goes to the phone: BookahCalendar's parse bug showed only there.
            if (findProperty("minifyDebug") != null) {
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(project(":design"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
