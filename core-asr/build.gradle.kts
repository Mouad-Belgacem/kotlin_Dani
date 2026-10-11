plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tarteelwarsh.tilawa.asr"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Le modele .onnx (248 Mo, copie dans src/androidTest/assets/model/ pour
    // les tests - jamais commite, voir mobile_app/.gitignore) est un binaire
    // deja dense : pas la peine d'essayer de le compresser dans l'APK de
    // test (aapt2 le ferait par defaut sinon, pour un gain nul).
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
    implementation(project(":core-logic"))   // RunAwareTokenizer (decode des ids CTC)
    implementation(project(":core-fbank"))
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")

    // Verifie sur Maven Central (maven-metadata.xml), pas suppose : le
    // groupId/artifactId existe reellement, versions 1.11.0 -> 1.29.0 au
    // moment ou ce module a ete ecrit. 1.20.0 choisie : stable (pas la toute
    // derniere, marge de recul sur d'eventuelles regressions recentes), et
    // le coeur C++ partage entre bindings Python/Android supporte l'opset 13
    // depuis bien avant cette version (le modele exporte avec
    // opset_version=13 tourne deja sous onnxruntime Python 1.23.2 localement,
    // cf. scripts/verify_onnx_export_standard.py).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
