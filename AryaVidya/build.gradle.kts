plugins {
    id("com.android.library")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.AryaVidya"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

version = 2

cloudstream {
    language = "hi"
    description = "Only Education And Live Stream"
    authors = listOf("Hungry Birds")

    status = 1 
    tvTypes = listOf(
        "Education",
        "Classes",
        "Live's",
        "Knowledge"
    )

    iconUrl = "https://raw.githubusercontent.com/techmind09/hungrysturdy/refs/heads/main/AryaVidya/icon.png"
}
