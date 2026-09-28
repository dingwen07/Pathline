import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Firebase services need google-services.json (gitignored, like the Maps key). Apply the plugins
// only when the file is present so CI / fresh checkouts still build without Firebase configured.
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    pluginManager.apply("com.google.gms.google-services")
    pluginManager.apply("com.google.firebase.crashlytics")
    pluginManager.apply("com.google.firebase.firebase-perf")
}

// The only bundled Maps Platform credential is restricted to the inexpensive Maps SDK for Android.
// Places and Routes read the user's Keystore-wrapped key at runtime and never use this value.
val mapsSdkApiKeyFromFile: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("MAPS_SDK_API_KEY", "")
val mapsSdkApiKey: String = providers.environmentVariable("PATHLINE_MAPS_SDK_API_KEY")
    .orNull?.takeIf { it.isNotBlank() } ?: mapsSdkApiKeyFromFile

android {
    namespace = "net.extrawdw.apps.locationhistory"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.extrawdw.apps.locationhistory"
        minSdk = 34
        targetSdk = 37
        versionCode = 26
        versionName = "1.9.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["MAPS_SDK_API_KEY"] = mapsSdkApiKey
        // Telemetry defaults ON for every build type
        manifestPlaceholders["FIREBASE_CRASHLYTICS_COLLECTION_ENABLED"] = true
        manifestPlaceholders["FIREBASE_PERFORMANCE_COLLECTION_ENABLED"] = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            ndk {
                debugSymbolLevel = "FULL"
            }
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
    androidResources {
        generateLocaleConfig = true
    }
    testOptions {
        // TimelineDryRunTest: forward -DdryRun* (dir/since/until) and the dump zone into the forked
        // unit-test JVM (it doesn't inherit -D otherwise). See scripts/timeline-dryrun.sh.
        unitTests.all { test ->
            System.getProperties().stringPropertyNames()
                .filter { it.startsWith("dryRun") }
                .forEach { test.systemProperty(it, System.getProperty(it)) }
            System.getProperty("user.timezone")?.let { test.systemProperty("user.timezone", it) }
        }
    }
}

// Emit reproducible, cacheable Room schemas for migration validation and tests.
room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    // Dependency injection (Hilt)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    // Persistence (Room + DataStore)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.datastore.preferences)

    // Encrypted database + SAF backup destinations
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.documentfile)

    // Passkey-based backup encryption (WebAuthn PRF via Credential Manager)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)

    // Location, maps, places
    implementation(libs.play.services.location)
    implementation(libs.maps.compose)
    implementation(libs.maps.compose.utils)
    implementation(libs.places)

    // Firebase is retained only for opt-out Crashlytics and Performance Monitoring telemetry.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)

    // Serialization & coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Runtime permissions in Compose
    implementation(libs.accompanist.permissions)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
