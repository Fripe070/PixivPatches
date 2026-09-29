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

    extendWith("extensions/pixiv.mpe")

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

        // 2. Hook ProfileApiModel.f()Z -> always return true
        val profileApiClass = mutableClassDefBy("Ljp/pxv/android/data/userstate/remote/dto/ProfileApiModel;")
        val fMethod = profileApiClass.methods.first { it.name == "f" && it.returnType == "Z" }
        fMethod.addInstructions(
            1,
            """
            const/4 v0, 0x1
            return v0
            """.trimIndent()
        )

        // 3. Hook domain entity ca8.<init> -> force isPremium (p6) to true
        val domainProfileClass = mutableClassDefByOrNull("Lca8;")
        domainProfileClass?.let { cls ->
            val ctor = cls.methods.firstOrNull { it.name == "<init>" }
            ctor?.addInstructions(
                1,
                "const/4 p6, 0x1"
            )
        }

        // 4. Hook MuteSettingResponse.a()I -> return 9999 (unlimited mute limit count)
        val muteSettingClass = mutableClassDefByOrNull("Ljp/pxv/android/data/mute/remote/dto/MuteSettingResponse;")
        muteSettingClass?.let { cls ->
            val aMethod = cls.methods.firstOrNull { it.name == "a" && it.returnType == "I" }
            aMethod?.addInstructions(
                1,
                """
                const/16 v0, 0x270f
                return v0
                """.trimIndent()
            )
        }

        // 5. Hook MuteLimitForTextApiModel -> return 9999 for both free and premium mute limits
        val muteTextLimitClass = mutableClassDefByOrNull("Ljp/pxv/android/data/mute/remote/dto/MuteLimitForTextApiModel;")
        muteTextLimitClass?.let { cls ->
            val aMethod = cls.methods.firstOrNull { it.name == "a" && it.returnType == "I" }
            aMethod?.addInstructions(
                1,
                """
                const/16 v0, 0x270f
                return v0
                """.trimIndent()
            )
            val bMethod = cls.methods.firstOrNull { it.name == "b" && it.returnType == "I" }
            bMethod?.addInstructions(
                1,
                """
                const/16 v0, 0x270f
                return v0
                """.trimIndent()
            )
        }

        // 6. Hook IllustBrowsingHistoryResponse.a()Ljava/util/List; -> return local history if server history is empty
        val historyResponseClass = mutableClassDefByOrNull("Ljp/pxv/android/data/browsinghistory/remote/dto/IllustBrowsingHistoryResponse;")
        historyResponseClass?.let { cls ->
            val aMethod = cls.methods.firstOrNull { it.name == "a" && it.returnType == "Ljava/util/List;" }
            aMethod?.addInstructions(
                1,
                """
                invoke-static {p0}, Lapp/morphe/extension/pixiv/premium/HistoryHelper;->getHistoryList(Ljava/lang/Object;)Ljava/util/List;
                move-result-object v0
                return-object v0
                """.trimIndent()
            )
        }
    }
}
