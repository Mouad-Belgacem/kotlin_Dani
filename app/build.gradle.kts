plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tarteelwarsh.tilawa"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tarteelwarsh.tilawa"
        minSdk = 26

        // arm64 only. onnxruntime-android ships native .so for arm64-v8a,
        // armeabi-v7a, x86 and x86_64; without this a universal APK carries all
        // four, of which a phone uses one. Together with int8 weights this is
        // what takes the APK from ~600MB to ~100MB. Every 64-bit Android device
        // since 2017 is arm64-v8a, and Play has required 64-bit since 2019.
        ndk { abiFilters += "arm64-v8a" }
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG - pour cacher les diagnostics dev (LatencyBadge)
        // d'un build de release reel, sans les retirer completement du code
        // (encore utiles en debug pour le developpement/mesure de latence).
        buildConfig = true
    }

    // Modeles .onnx embarques dans les assets de PRODUCTION (pas juste des
    // tests - c'est l'app reelle qui doit fonctionner hors-ligne) - meme
    // raisonnement que core-asr/core-segmenter : pas la peine de les
    // compresser dans l'APK (deja denses), et ORT a besoin d'y acceder via
    // un vrai chemin fichier apres materialisation.
    androidResources {
        noCompress += "onnx"
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
    implementation(project(":core-logic"))
    implementation(project(":core-audio"))
    implementation(project(":core-fbank"))
    implementation(project(":core-asr"))
    implementation(project(":core-pipeline"))

    // onnxruntime-android est `implementation` (pas `api`) dans core-asr/
    // core-segmenter - invisible depuis app sans redeclaration explicite,
    // meme situation que core-pipeline/build.gradle.kts. Meme version deja
    // validee (voir core-asr/README.md).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // material-icons-extended REMOVED. It ships ~5000 generated icons, each a
    // Kotlin object that builds a vector path, and this app uses 9 (Close, Menu,
    // Mic, Search, Stop, History, Tune, Visibility, VisibilityOff). It was the
    // bulk of a 43.9 MB classes.dex in a 179 MB APK.
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
