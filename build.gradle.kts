// Root build file. Plugins are declared here (apply false) so the app module can apply them
// through the version catalog. AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no
// org.jetbrains.kotlin.android plugin anywhere in this project.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
