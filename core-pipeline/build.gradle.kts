plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tarteelwarsh.tilawa.pipeline"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Les modeles .onnx (Zipformer + segmenter, copies dans
    // src/androidTest/assets/model/ pour le test fonctionnel de bout en
    // bout - jamais commites, voir mobile_app/.gitignore).
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
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // `implementation` (pas `api`) dans core-asr/core-segmenter : invisible
    // depuis androidTest sans redeclaration explicite ici. Meme version deja
    // validee (voir core-asr/README.md).
    androidTestImplementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
