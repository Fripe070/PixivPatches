package app.morphe.patches.pixiv

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.ResourcePatch
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.AppTarget
import org.w3c.dom.Element

val pixivExportPatch: ResourcePatch = resourcePatch(
    name = "Developer Tools",
    description = "Enables debuggable flags in AndroidManifest and exports settings activities for local testing and debugging.",
    default = false
) {
    compatibleWith(
        Compatibility(
            name = "Pixiv",
            packageName = "jp.pxv.android",
            targets = listOf(AppTarget("6.196.0"))
        )
    )
    execute {
        document("AndroidManifest.xml").use { doc ->
            val appNode = doc.getElementsByTagName("application").item(0) as? Element
            appNode?.setAttribute("android:debuggable", "true")

            val activityNodes = doc.getElementsByTagName("activity")
            for (i in 0 until activityNodes.length) {
                val element = activityNodes.item(i) as? Element ?: continue
                val name = element.getAttribute("android:name")
                if (name.contains("AiShowSettingActivity") || name.contains("SettingActivity")) {
                    element.setAttribute("android:exported", "true")
                }
            }
        }
    }
}
