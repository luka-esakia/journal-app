plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * A stable signing key, when one is available.
 *
 * Android refuses to install an update whose signature differs from the installed app. CI
 * generates a throwaway debug keystore on every runner, so each build was signed with a different
 * key and every new APK had to be uninstalled first. Pointing both build types at one committed
 * or CI-provisioned keystore makes updates install over the top, as they should.
 */
val sharedKeystore = rootProject.file("keystore/mindjournal.jks")
val hasSharedKeystore = sharedKeystore.exists()

/**
 * Password used when none is supplied by the environment.
 *
 * This is a self-signed key for installing personal builds — it grants no publishing rights and
 * protects nothing. A Play Store key must come from `KEYSTORE_PASSWORD` / `KEY_PASSWORD` and must
 * never be committed.
 */
val DEFAULT_KEYSTORE_SECRET = "mindjournal"

android {
    namespace = "com.journal.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.journal.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"

        // The whole UI is Georgian; keep only the resources we actually ship.
        resourceConfigurations += setOf("ka", "en")
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasSharedKeystore) {
            create("shared") {
                // An unset GitHub secret still exports an *empty* env var, so blank must fall
                // through to the default rather than being used as the password.
                fun env(name: String, fallback: String): String =
                    System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

                storeFile = sharedKeystore
                // PKCS12 despite the .jks extension — what modern keytool writes by default,
                // and what this project's key actually is. Stated explicitly so the build does
                // not depend on the JDK's default keystore type.
                storeType = "PKCS12"
                storePassword = env("KEYSTORE_PASSWORD", DEFAULT_KEYSTORE_SECRET)
                keyAlias = env("KEY_ALIAS", "mindjournal")
                keyPassword = env("KEY_PASSWORD", DEFAULT_KEYSTORE_SECRET)
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            if (hasSharedKeystore) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Falls back to the ephemeral debug key so a fresh clone still builds; that build
            // just won't be update-compatible with previous ones.
            signingConfig = if (hasSharedKeystore) {
                signingConfigs.getByName("shared")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=androidx.compose.ui.text.ExperimentalTextApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Core / lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Encrypted preferences for the OpenRouter API key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // App lock: biometrics with device PIN/pattern/password fallback.
    implementation("androidx.biometric:biometric:1.1.0")

    // biometric 1.1.0 pins androidx.fragment 1.2.5 (2020). Nothing else in this project pulls
    // fragment, so that stale version would win — and a FragmentActivity from 1.2.5 running
    // against activity 1.9.2 does not install the ViewTree owners that Compose's setContent
    // requires, which crashes the app during launch. Pin fragment alongside activity instead.
    implementation("androidx.fragment:fragment-ktx:1.8.4")

    // Scheduling (AlarmManager primary, WorkManager as the resilient fallback)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // The android.jar on the unit-test classpath ships org.json as throwing stubs; the real
    // implementation shadows them so the exporter can be tested on the JVM.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.02"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
