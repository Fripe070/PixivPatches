package app.morphe.patches.pixiv.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch

val pixivPremiumPatch: BytecodePatch = bytecodePatch(
    name = "Pixiv Premium Features",
    description = "Unlocks popularity sort sorting (popular_desc) in search, removes mute limits, and emulates client-side Pixiv Premium membership status.",
    default = true
) {
    compatibleWith(
        Compatibility(
            name = "Pixiv",
            packageName = "jp.pxv.android",
            targets = listOf(AppTarget("6.196.0"))
        )
    )

    execute {
        // 1. Hook OAuthUser.l0()Z -> always return true
        val oauthUserClass = mutableClassDefBy("Ljp/pxv/android/domain/auth/entity/OAuthUser;")
        val l0Method = oauthUserClass.methods.first { it.name == "l0" && it.returnType == "Z" }
        l0Method.addInstructions(
            1,
            """
            const/4 v0, 0x1
            return v0
            """.trimIndent()
        )

        // 2. Hook PixivProfile constructor: set isPremium = true
        val pixivProfileClass = mutableClassDefBy("Ljp/pxv/android/domain/commonentity/PixivProfile;")
        val profileCtor = pixivProfileClass.methods.first { it.name == "<init>" }
        profileCtor.addInstructions(
            1,
            """
            const/4 v0, 0x1
            iput-boolean v0, p0, Ljp/pxv/android/domain/commonentity/PixivProfile;->isPremium:Z
            """.trimIndent()
        )

        // 3. Hook ProfileApiModel.f()Z -> always return true
        val profileApiClass = mutableClassDefBy("Ljp/pxv/android/data/userstate/remote/dto/ProfileApiModel;")
        val fMethod = profileApiClass.methods.first { it.name == "f" && it.returnType == "Z" }
        fMethod.addInstructions(
            1,
            """
            const/4 v0, 0x1
            return v0
            """.trimIndent()
        )

        // 4. Hook tt7 (Pixiv Account Session Manager): force isPremium flag (h:Z) to true
        val tt7Class = mutableClassDefBy("Ltt7;")
        val tt7Ctor = tt7Class.methods.first { it.name == "<init>" }
        tt7Ctor.addInstructions(
            1,
            """
            const/4 v0, 0x1
            iput-boolean v0, p0, Ltt7;->h:Z
            """.trimIndent()
        )

        val tt7CMethod = tt7Class.methods.firstOrNull { it.name == "c" }
        tt7CMethod?.addInstructions(
            1,
            """
            const/4 v0, 0x1
            iput-boolean v0, p0, Ltt7;->h:Z
            """.trimIndent()
        )
    }
}
