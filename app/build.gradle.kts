plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.skul9x.doctruyen"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.skul9x.doctruyen"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = (project.findProperty("STORE_FILE") as? String)
                ?: System.getenv("STORE_FILE")
                ?: "skul9x.jks"
            val keystoreFile = rootProject.file(storeFilePath).takeIf { it.exists() }
                ?: project.file(storeFilePath).takeIf { it.exists() }
                ?: file(storeFilePath)

            if (keystoreFile.exists()) {
                storeFile = keystoreFile
            }
            storePassword = (project.findProperty("STORE_PASSWORD") as? String)
                ?: System.getenv("STORE_PASSWORD")
                ?: ""
            keyAlias = (project.findProperty("KEY_ALIAS") as? String)
                ?: System.getenv("KEY_ALIAS")
                ?: ""
            keyPassword = (project.findProperty("KEY_PASSWORD") as? String)
                ?: System.getenv("KEY_PASSWORD")
                ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    androidResources {
        noCompress += listOf("onnx", "data", "npz", "json")
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // ONNX Runtime Android
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // CardView
    implementation("androidx.cardview:cardview:1.0.0")

    // Media
    implementation("androidx.media:media:1.7.0")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}