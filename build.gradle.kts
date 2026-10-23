plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.example.w0wingen"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.w0wingen"
        minSdk = 30
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("richingen.keystore")
            storePassword = "richingen"
            keyAlias = "richingen"
            keyPassword = "richingen"
        }
    }

    buildTypes {
        getByName("debug") {
            // Her derlemede aynı imza: yeni APK eskisinin üstüne kurulur, ayarlar ve kayıtlar silinmez
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // ATK / DEF sayilarini okumak icin cihaz uzerinde calisan metin tanima (internet gerekmez)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
}
