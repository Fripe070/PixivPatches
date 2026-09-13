package app.morphe.patches.pixiv

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch

val pixivAdblockerPatch: BytecodePatch = bytecodePatch(
    name = "Pixiv Adblocker",
    description = "Eliminates bottom advertising banners across all screens without layout padding, suppresses rate-this-app dialogs, and hides sponsored works in feeds.",
    default = true
) {
    compatibleWith(
        Compatibility(
            name = "Pixiv",
            packageName = "jp.pxv.android",
            targets = listOf(AppTarget("6.196.0"))
        )
    )
    extendWith("extensions/pixiv.mpe")

    execute {
        // --- Hook 1: Neutralize bottom overlay banner ad constructors and collapse containers ---
        val bannerOverlayClasses = listOf(
            "Ljp/pxv/android/feature/advertisement/view/OverlayAppLovinView;",
            "Ljp/pxv/android/feature/advertisement/view/OverlayADGAutoRotationView;",
            "Ljp/pxv/android/feature/advertisement/view/OverlayAdgTamView;",
            "Ljp/pxv/android/feature/advertisement/view/YufulightOverlayAdView;"
        )

        for (overlayClassName in bannerOverlayClasses) {
            val overlayClass = mutableClassDefByOrNull(overlayClassName) ?: continue
            val constructors = overlayClass.methods.filter { it.name == "<init>" }
            for (ctor in constructors) {
                ctor.addInstructions(
                    1,
                    """
                    invoke-static {p0}, Lapp/morphe/extension/pixiv/adblock/AdblockHelper;->hideBanner(Landroid/view/View;)V
                    return-void
                    """.trimIndent()
                )
            }
        }

        // --- Hook 2: MainActivity.onCreate collapse ad_container (0x7f0a004f) immediately ---
        val mainActivityClass = mutableClassDefBy("Ljp/pxv/android/MainActivity;")
        val mainOnCreate = mainActivityClass.methods.first { it.name == "onCreate" }
        mainOnCreate.addInstructions(
            1,
            "invoke-static {p0}, Lapp/morphe/extension/pixiv/adblock/AdblockHelper;->collapseMainActivityBanner(Landroid/app/Activity;)V"
        )

        // --- Hook 3: Suppress in-app review request dialog (b.onCreate rating observer) ---
        val lifecycleBClass = mutableClassDefBy("Ljp/pxv/android/feature/bottomnavigationroot/lifecycle/b;")
        val bOnCreate = lifecycleBClass.methods.first { it.name == "onCreate" }
        val bInstructions = bOnCreate.implementation?.instructions

        // Find instruction accessing Leza (rating prompt event stream)
        val lezaIndex = bInstructions?.indexOfFirst {
            it is com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction &&
            (it.reference as? com.android.tools.smali.dexlib2.iface.reference.FieldReference)?.let { field ->
                field.name == "l" && field.type == "Leza;"
            } == true
        } ?: -1

        if (lezaIndex != -1) {
            bOnCreate.addInstructions(
                lezaIndex,
                "return-void"
            )
        }

        // --- Hook 4: Hide sponsored works and promotional cards in feeds and detail screen ---
        val sponsoredClasses = listOf(
            "Ljp/pxv/android/feature/commonlist/recyclerview/baserecycler/SelfServeItemViewHolder;",
            "Ljp/pxv/android/feature/commonlist/recyclerview/baserecycler/RectangleAdViewHolder;",
            "Ljp/pxv/android/feature/illustviewer/detail/IllustDetailAdvertisementSolidItem;"
        )

        for (cardClassName in sponsoredClasses) {
            val cardClass = mutableClassDefByOrNull(cardClassName) ?: continue
            val showMethod = cardClass.methods.firstOrNull { it.name == "show" }
            showMethod?.addInstructions(
                1,
                """
                invoke-static {p0}, Lapp/morphe/extension/pixiv/adblock/AdblockHelper;->hideSponsoredItem(Ljava/lang/Object;)V
                return-void
                """.trimIndent()
            )
        }
    }
}
