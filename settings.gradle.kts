pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tilawa"

// Pur Kotlin/JVM - tokenizer, phonetiseur (track_phase1 + bab_alhuruf), Finder/Corrector.
// Testable en JUnit desktop, sans emulateur, contre les sorties Python de Dani_app
// (voir core-logic/README.md - discipline des tests dores, obligatoire pour ce module).
include(":core-logic")

// Modules Android - chacun encapsule une brique avec une vraie complexite propre.
include(":core-audio")       // Capture micro (AudioRecord), ring buffer, chunking
include(":core-fbank")       // Extraction Fbank streaming (kaldi-native-fbank)
// core-segmenter REMOVED: the waqf-aware checkpoint marks segment boundaries
// itself (it predicts "s" where the reciter stops), so an acoustic VAD is no
// longer part of the pipeline. The module had no Kotlin source anyway.
include(":core-asr")         // Zipformer streaming ONNX (98 tenseurs d'etat), decodeur CTC streaming
include(":core-pipeline")    // Orchestrateur - equivalent de Session/process_chunk (Dani_app)

include(":app")
