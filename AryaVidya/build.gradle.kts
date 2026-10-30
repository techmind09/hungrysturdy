plugins {
    id("com.lagradost.cloudstream3.gradle")
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
