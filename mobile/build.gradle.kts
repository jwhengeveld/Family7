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

// De Cast-ontvanger. Standaard de Default Media Receiver van Google, die
// elke Chromecast en Google TV kent. Vraagt de stream van Streampartner ooit
// iets wat die ontvanger niet kan (bijvoorbeeld een eigen Referer), dan kan
// hier het ID van een eigen, in de Cast Developer Console geregistreerde
// ontvanger komen: ./gradlew -Pfamily7.castReceiverId=ABCD1234 ...
val castReceiverId: String =
    (project.findProperty("family7.castReceiverId") as String?)?.takeIf { it.isNotBlank() }
        ?: "CC1AD845"

android {
    namespace = "nl.family7.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.family7.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.3.0"

        buildConfigField("String", "CAST_RECEIVER_ID", "\"$castReceiverId\"")
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
    implementation(libs.androidx.appcompat)
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
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Afspelen op de telefoon
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)

    // Casten naar Chromecast / Google TV
    implementation(libs.androidx.media3.cast)
    implementation(libs.androidx.mediarouter)
    implementation(libs.play.services.cast.framework)

    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
