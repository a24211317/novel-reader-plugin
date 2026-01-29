plugins {
    id("org.jetbrains.intellij.platform") version "2.11.0"
    kotlin("jvm") version "1.9.24"
}
version = "1.0.1"
repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea("2024.3")
    }
}

kotlin { jvmToolchain(17) }
