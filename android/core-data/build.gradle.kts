plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.bookah.tasks.data"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.android)
    api(libs.biweekly)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The real-data tests read the folder named by BOOKAH_PRIVATE_DIR (outside Git). Making it part of the test task, so a cached "skipped" result
// is never reused once it is set.
tasks.configureEach {
    if (name.startsWith("test") && name.endsWith("UnitTest")) inputs.property("privateDir", providers.environmentVariable("BOOKAH_PRIVATE_DIR").orElse(""))
}
