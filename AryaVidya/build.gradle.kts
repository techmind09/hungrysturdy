version = 2

cloudstream {
    language = "hi"
    // All of these properties are optional, you can safely remove them

    description = "Only Education And Live Stream"
     authors = listOf("Hungry Birds")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Education",
        "Classes",
        "Live's",
        "Knowledge"
    )

    iconUrl = "https://raw.githubusercontent.com/techmind09/hungrysturdy/refs/heads/main/AryaVidya/icon.png"
}
