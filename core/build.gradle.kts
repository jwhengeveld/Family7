plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// De datalaag die de TV-app en de telefoonapp delen: inloggen, de catalogus,
// Mijn lijst, de livestream en het uitpakken van de Streampartner-speler.
android {
    namespace = "com.xiappdesign.family7.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // De apps gebruiken OkHttp ook zelf (de speler haalt de stream via dezelfde client).
    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

// De sitewachter (LiveSiteTest) draait alleen met -Pfamily7.live=true.
tasks.withType<Test>().configureEach {
    systemProperty("family7.live", project.findProperty("family7.live")?.toString() ?: "false")
}
