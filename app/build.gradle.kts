plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.pocet.screenmonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocet.screenmonitor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        multiDexEnabled = true

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas".toString())
            }
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
        viewBinding = true
    }

    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,NOTICE}"
        }
        jniLibs {
            pickFirsts += "lib/**/*"
        }
    }

    aaptOptions {
        noCompress("tflite", "lite")
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.51")
    kapt("com.google.dagger:hilt-compiler:2.51")

    // ML Kit Text Recognition (on-device) - TODO: Fix dependency
    // implementation("com.google.mlkit:text-recognition:19.0.0")

    // Google Auth & Sheets API
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    implementation("com.google.api-client:google-api-client:1.32.1")
    implementation("com.google.api-client:google-api-client-android:1.32.1")
    implementation("com.google.http-client:google-http-client-android:1.32.1")
    implementation("com.google.oauth-client:google-oauth-client:1.32.1")
    implementation("com.google.apis:google-api-services-sheets:v4-rev20230105-2.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // WorkManager for periodic background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Room Database for local caching
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // EncryptedSharedPreferences for secure token storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Timber for logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.robolectric:robolectric:4.11")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.51")
    kaptAndroidTest("com.google.dagger:hilt-compiler:2.51")
}

kapt {
    correctErrorTypes = true
    javacOptions {
        option("-Xlint:unchecked")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs += "-Xopt-in=kotlin.RequiresOptIn"
    }
}