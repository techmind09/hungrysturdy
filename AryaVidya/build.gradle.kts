plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    setReadme("README.md")
    // Plugins metadata configuration
    authors = listOf("techmind09")
}

android {
    namespace = "com.techmind09.aarvavidya"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_X
        targetCompatibility = JavaVersion.VERSION_1_X
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(com.lagradost.cloudstream3.utils.AppUtils.API_IMPLEMENTATION ?: "")
    // Jo bhi Jsoup ya dependencies chahiye wo yahan add karein
}
