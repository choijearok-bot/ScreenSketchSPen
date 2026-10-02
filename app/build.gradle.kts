plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.screensketch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.screensketch"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.5.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
