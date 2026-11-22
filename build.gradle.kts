// CyberDrill — root build file.
// Toolchain (owner's proven pattern): AGP 8.5.2 + Kotlin 2.0.20, JDK 17, compileSdk 35.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}
