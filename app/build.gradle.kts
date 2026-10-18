plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.retrodroid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.retrodroid.app"
        minSdk = 29
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
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST", "META-INF/AL2.0", "META-INF/LGPL2.1"
            )
        }
    }
}

dependencies {
    // O workflow descobre sozinho uma versão que existe no JitPack e passa em LIBRETRO_DEP.
    implementation(System.getenv("LIBRETRO_DEP") ?: "com.github.swordfish90:libretrodroid:0.13.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // ler .zip (nativo) e .7z (LZMA)
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
