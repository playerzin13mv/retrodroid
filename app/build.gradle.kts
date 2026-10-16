plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.retrodroid.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.retrodroid.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // Os cores baixados pelo workflow são só arm64 (praticamente todo celular atual)
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Obrigatório: os cores (.so) precisam ser extraídos pra pasta nativa
    // para o LibretroDroid conseguir carregá-los pelo caminho do arquivo.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    // ⚠ Versão não verificada. Se der erro de "could not resolve", veja a mais nova em
    // https://jitpack.io/#Swordfish90/LibretroDroid
    implementation("com.github.swordfish90:libretrodroid:0.13.0")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
