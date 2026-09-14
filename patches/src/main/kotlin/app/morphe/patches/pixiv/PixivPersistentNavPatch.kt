package app.morphe.patches.pixiv

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch

val pixivPersistentNavPatch: BytecodePatch = bytecodePatch(
    name = "Pixiv Persistent Navigation",
    description = "Keeps the primary bottom navigation bar visible, docked, and functional when navigating into submenus and drill-down views such as SearchResultActivity and RankingActivity.",
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
        // --- Hook 1: Hook base FragmentActivity (androidx.fragment.app.r.onStart) ---
        // dv inherits androidx.fragment.app.r, covering SearchResultActivity and RankingActivity.
        // Hooking onStart on r ensures the persistent navigation bar is attached as soon as any submenu activity's view hierarchy becomes active.
        val rClass = mutableClassDefBy("Landroidx/fragment/app/r;")
        val onStartMethod = rClass.methods.first { it.name == "onStart" && it.parameterTypes.isEmpty() }

        onStartMethod.addInstructions(
            1,
            "invoke-static {p0}, Lapp/morphe/extension/pixiv/navigation/PersistentNavHelper;->attachBottomNav(Landroid/app/Activity;)V"
        )
    }
}

