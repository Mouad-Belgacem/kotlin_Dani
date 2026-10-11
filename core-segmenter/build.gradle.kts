plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tarteelwarsh.tilawa.segmenter"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Le modele .onnx (segmenter, ~291 Mo, copie dans src/androidTest/assets/model/
    // pour les tests - jamais commite, voir mobile_app/.gitignore), meme
    // raisonnement que core-asr : deja dense, pas la peine de le compresser
    // dans l'APK de test.
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
    implementation(project(":core-logic"))   // JsonParser (meme reutilisation que core-asr/StreamingMeta.kt)
    implementation(project(":core-fbank"))
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")

    // Meme version que core-asr, deja verifiee reelle sur Maven Central et
    // deja validee sur device (voir core-asr/README.md) - pas de nouvelle
    // verification necessaire, meme artefact reutilise.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
