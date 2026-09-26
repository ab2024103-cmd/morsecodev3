// Root build file. No plugins are applied here; `:app` is the only module (§3.1).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
