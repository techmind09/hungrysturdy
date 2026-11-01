plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.lagradost.cloudstream3.gradle") version "1.0.5" apply false // Agar root me plugin version define karte hain
}

tasks.register("clean", Delete::class.java) {
    delete(rootProject.layout.buildDirectory)
}
