version = 5

cloudstream {
    language = "hi"
    // All of these properties are optional, you can safely remove them

    description = "Movies and Series upto 4K"
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
        "TvSeries",
        "Movies",
        "AsianDrama",
        "Anime"
    )

    iconUrl = "https://raw.githubusercontent.com/techmind09/multiprovide/refs/heads/main/Multiplex/icon.png"
}
