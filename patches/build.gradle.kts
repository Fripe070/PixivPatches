group = "app.morphe"
version = "1.0.1"


patches {
    about {
        name = "Pixiv Patches"
        description = "Morphe patches for Pixiv Android (AI flagger, adblocking, persistent navigation)"
        source = "https://github.com/Fripe070/PixivPatches"
        author = "Fripe070"
        contact = "na"
        website = "https://github.com/Fripe070/PixivPatches"
        license = "GNU General Public License v3.0"
    }
}

dependencies {
    implementation(libs.guava)
    implementation(libs.morphe.patches.library)
    implementation(libs.smali)
}
