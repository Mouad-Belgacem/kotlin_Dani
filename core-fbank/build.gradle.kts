plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tarteelwarsh.tilawa.fbank"
    compileSdk = 34
    // Installes explicitement dans cet environnement (C:\Android) pour ce
    // module - aucun artefact Android/JNI publie pour kaldi-native-fbank
    // (verifie : ni le depot lui-meme, ni sherpa-onnx qui l'embarque, ne
    // publient d'AAR/Maven - "clonez et compilez avec le NDK" est la seule
    // voie documentee). D'ou la compilation depuis les sources via CMake
    // FetchContent, voir src/main/cpp/CMakeLists.txt.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64-v8a : le device de test (SM-N970F) ; armeabi-v7a : couverture
        // large sans cout significatif (meme sources C++ portables, "sans
        // dependances externes" par construction).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
                // Android 15+ may use 16 KB memory pages. Without this the
                // linker aligns LOAD segments to 4 KB, and the OS reports
                // "LOAD segment not aligned" and falls back to page-size
                // compatibility mode. NDK r27 turns this into
                // -Wl,-z,max-page-size=16384 (build/cmake/flags.cmake:35).
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
