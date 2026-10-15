plugins {
    alias(libs.plugins.android.library) apply false
}

fun git(vararg args: String) =
    providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim()

// rN-<shortrev>, N = number of commits.
allprojects {
    version = "r${git("rev-list", "--count", "HEAD")}-${git("rev-parse", "--short", "HEAD")}"
}
