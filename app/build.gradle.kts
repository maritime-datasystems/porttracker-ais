import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.porttracker.ais"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.porttracker.ais"
        minSdk = 23
        targetSdk = 36
        versionCode = 31
        versionName = "3.1.0"

    }

    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") {
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
            dimension = "distribution"
            buildConfigField("boolean", "ALLOW_SIDELOAD_UPDATES", "true")
        }
        create("play") {
            // The prebuilt x86_64 FRP executable has only 4 KB ELF alignment.
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
            dimension = "distribution"
            // Separate store identity (porttracker.co, reversed). The fleet's
            // sideload installs stay com.porttracker.ais, so both can coexist
            // and nobody has to uninstall (and lose station settings) to try
            // the store build. Permanent once uploaded to Play.
            applicationId = "co.porttracker.ais"
            // Play's automatic protection requires API 24+. The sideload
            // flavor keeps the default minSdk for older fleet phones.
            minSdk = 24
            buildConfigField("boolean", "ALLOW_SIDELOAD_UPDATES", "false")
        }
    }

    // Optional local upload key; never use the debug certificate for Play.
    val signingFile = rootProject.file("signing.properties")
    if (signingFile.exists()) {
        val signing = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs.create("upload") {
            storeFile = rootProject.file(requireNotNull(signing.getProperty("storeFile")))
            storePassword = requireNotNull(signing.getProperty("storePassword"))
            keyAlias = requireNotNull(signing.getProperty("keyAlias"))
            keyPassword = requireNotNull(signing.getProperty("keyPassword"))
        }
    }

    packaging { jniLibs.useLegacyPackaging = true }

    buildTypes {
        release {
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
    
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    ndkVersion = "28.2.13676358"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.browser:browser:1.7.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
