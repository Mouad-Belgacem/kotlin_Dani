// Declaration des plugins au niveau racine (apply false : chaque module les applique lui-meme).
// Versions a reverifier au moment ou le projet sera reellement compile pour la premiere fois -
// ce squelette n'a pas encore ete build.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.20" apply false
    // Kotlin 2.0+ : le compilateur Compose est un plugin Kotlin a part entiere
    // (plus l'ancien composeOptions{kotlinCompilerExtensionVersion}), epingle
    // a la MEME version que org.jetbrains.kotlin.android ci-dessus.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
