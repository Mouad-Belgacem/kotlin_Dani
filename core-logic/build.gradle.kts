plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    // Environnement de build contraint en RAM (~3,6 Go libres observes) : un
    // seul fork de test JVM, heap modeste explicite (les executeurs de test
    // Gradle n'heritent pas de org.gradle.jvmargs par defaut) - sans ça, les
    // executeurs de test (plusieurs en parallele par defaut) echouent avec
    // "insufficient memory" avant meme de charger les fixtures dorees.
    maxParallelForks = 1
    minHeapSize = "128m"
    maxHeapSize = "512m"
    jvmArgs("-XX:MaxMetaspaceSize=192m")
}
