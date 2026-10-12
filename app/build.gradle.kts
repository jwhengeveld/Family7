import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Ondertekeningsgegevens komen uit keystore.properties, dat buiten de repo blijft.
// Ontbreekt dat bestand, dan wordt de release niet ondertekend en kan er nog wel
// gebouwd worden (bijvoorbeeld op een schone checkout of in CI).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

// De testeditie voor een eigen Play-account: dezelfde app met een eigen ID, zodat
// nl.family7.tv vrij blijft voor Family7 zelf. In Play is het één app met de
// telefoon (nl.xiappdesign.family); deze tv-build gaat via de Android TV-track.
//   ./gradlew -Pfamily7.edition=test assembleRelease bundleRelease
val isTestEdition = project.findProperty("family7.edition") == "test"
val TV_VERSION_OFFSET = 1_000_000

android {
    namespace = "nl.family7.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = if (isTestEdition) "nl.xiappdesign.family" else "nl.family7.tv"
        resValue("string", "app_name", "Family7")
        minSdk = 24
        targetSdk = 35
        // In de testeditie delen telefoon en tv één app in Play; daar moet elk
        // versienummer uniek zijn, dus de tv-builds zitten in een eigen reeks.
        versionCode = 16 + if (isTestEdition) TV_VERSION_OFFSET else 0
        versionName = "1.4.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // v2 is genoeg vanaf API 24; v3 maakt het later vervangen van
                // de sleutel mogelijk zonder dat installaties breken.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // Nodig voor BuildConfig.DEBUG: het meelezen van netwerkverkeer staat
        // alleen tijdens ontwikkelen aan.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":brand"))
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.navigation.compose)

    // Media3 ExoPlayer for HLS TV Video Playback
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer.hls)


    // Async Image loading
    implementation(libs.coil.compose)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
