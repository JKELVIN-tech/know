plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.knowapp.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.knowapp.android"
        minSdk = 26
        targetSdk = 35

        // Same backend the web app talks to — see backend/app/core/config.py's
        // CORS_ORIGINS for the web side; this app authenticates with a Bearer
        // token instead of the cookie flow, so CORS doesn't apply to it.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.0"
        buildConfigField("String", "GIT_SHA", "\"${System.getenv("GITHUB_SHA") ?: "dev"}\"")
        buildConfigField("String", "API_BASE_URL", "\"https://teach-backend-roza.onrender.com\"")
    }

    // Fixed key so every CI build installs over the previous one
    signingConfigs {
        create("shared") {
            // A private key from the build environment wins; the committed debug key is only the fallback
            val privateStore = System.getenv("KNOW_KEYSTORE_PATH")
            if (privateStore != null && file(privateStore).exists()) {
                storeFile = file(privateStore)
                storePassword = System.getenv("KNOW_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KNOW_KEY_ALIAS")
                keyPassword = System.getenv("KNOW_KEY_PASSWORD")
            } else {
                storeFile = file("know-debug.keystore")
                storePassword = "knowdebug"
                keyAlias = "know"
                keyPassword = "knowdebug"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Compose compiler version is managed automatically by the
    // org.jetbrains.kotlin.plugin.compose plugin (Kotlin 2.0+), matched to
    // the Kotlin version — no composeOptions block needed anymore.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Networking — Retrofit + OkHttp + Gson (Retrofit/Square's own official
    // converter — safer coordinate choice than a third-party interop lib).
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Token storage — EncryptedSharedPreferences, since this holds a live
    // session token for a financial-audit app.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Image loading for post/news photos (Supabase Storage URLs).
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// Surface Kotlin compile errors as CI annotations so a failed build explains itself
tasks.matching { it.name == "compileDebugKotlin" }.configureEach {
    logging.addStandardErrorListener(
        StandardOutputListener { message ->
            val text = message.toString().trim()
            if (text.startsWith("e: ")) println("::error::" + text.replace("\n", " ").take(450))
        },
    )
}
