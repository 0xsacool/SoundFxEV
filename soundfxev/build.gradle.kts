plugins {
    id("com.android.application") version "9.4.1"
}
android {
    namespace = "com.sacool.soundfxev"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.sacool.soundfxev"
        minSdk = 28
        targetSdk = 32
        versionCode = 3
        versionName = "0.1.0-alpha.3"
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
}
