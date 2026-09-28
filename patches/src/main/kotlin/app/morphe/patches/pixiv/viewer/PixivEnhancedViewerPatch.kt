package app.morphe.patches.pixiv.viewer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch

val pixivEnhancedViewerPatch: BytecodePatch = bytecodePatch(
    name = "Pixiv Enhanced Viewer & Instant Zoom",
    description = "Displays the standard-resolution artwork as an instant placeholder while the full-resolution image loads, allows immediate pinch-to-zoom/pan preserving zoom coordinates upon high-res load, shows a discreet loading indicator, enables swipe-down to dismiss fullscreen view, and adds quick-peek in-place zoom to artwork detail.",
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
        // --- Hook 1: DetailImageViewHolder.bind (In-place quick-peek zoom on artwork detail screen) ---
        val detailImageHolderClass = mutableClassDefBy("Ljp/pxv/android/feature/illustviewer/detail/DetailImageViewHolder;")
        val holderBindMethod = detailImageHolderClass.methods.first { it.name == "bind" && it.parameterTypes.size == 1 }
        holderBindMethod.addInstructions(
            1,
            "invoke-static/range {p0 .. p1}, Lapp/morphe/extension/pixiv/viewer/EnhancedViewerHelper;->onDetailImageBound(Ljava/lang/Object;Ljava/lang/Object;)V"
        )

        // --- Hook 2: FullScreenImageActivity.onCreate (Swipe-down to dismiss gesture) ---
        val fullScreenActivityClass = mutableClassDefBy("Ljp/pxv/android/feature/illustviewer/fullscreen/FullScreenImageActivity;")
        val onCreateMethod = fullScreenActivityClass.methods.first { it.name == "onCreate" && it.parameterTypes.size == 1 }
        onCreateMethod.addInstructions(
            1,
            "invoke-static {p0}, Lapp/morphe/extension/pixiv/viewer/EnhancedViewerHelper;->onFullScreenCreated(Landroid/app/Activity;)V"
        )

        // --- Hook 3: zr4.instantiateItem (Fullscreen instant placeholder & discreet loading badge) ---
        val zr4Class = mutableClassDefBy("Lzr4;")
        val instantiateMethod = zr4Class.methods.first { it.name == "instantiateItem" }
        val instantiateReturnIdx = instantiateMethod.implementation?.instructions?.indexOfLast {
            it.opcode.name.startsWith("return")
        } ?: -1
        if (instantiateReturnIdx >= 0) {
            instantiateMethod.addInstructions(
                instantiateReturnIdx,
                "invoke-static {v3, v4}, Lapp/morphe/extension/pixiv/viewer/EnhancedViewerHelper;->onFullScreenItemCreated(Ljava/lang/Object;Ljava/lang/Object;)V"
            )
        }

        // --- Hook 4: yr4.d (Full-res image swap, matrix & zoom preservation, loading badge dismissal) ---
        val yr4Class = mutableClassDefBy("Lyr4;")
        val dMethod = yr4Class.methods.first { it.name == "d" }
        dMethod.addInstructions(
            1,
            "invoke-static {p0}, Lapp/morphe/extension/pixiv/viewer/EnhancedViewerHelper;->onFullResLoaded(Ljava/lang/Object;)V"
        )
    }
}
