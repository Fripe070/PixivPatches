package app.morphe.extension.pixiv.navigation

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup

object PersistentNavHelper {

    private const val TAG_PERSISTENT_NAV = "morphe_persistent_bottom_nav"

    @JvmStatic
    fun attachBottomNav(activity: Activity?) {
        attachBottomNav(activity, 0)
    }

    @JvmStatic
    fun attachBottomNav(activity: Activity?, currentTabId: Int) {
        if (activity == null) return
        try {
            val activityName = activity.javaClass.name
            // MainActivity already has native bottom navigation; skip injection
            if (activityName.endsWith("MainActivity") || activityName.contains(".MainActivity")) {
                return
            }

            val contentView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

            // Prevent duplicate attachments on re-start
            if (contentView.findViewWithTag<View>(TAG_PERSISTENT_NAV) != null) {
                return
            }

            // Baseline navigation attachment stub for Milestone 1
            // Full navigation layout and intent dispatching will be expanded in Milestone 4
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun dispatchTabNavigation(activity: Activity?, tabId: Int) {
        if (activity == null) return
        try {
            val intent = Intent(activity, Class.forName("jp.pxv.android.MainActivity"))
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            intent.putExtra("target_tab_id", tabId)
            activity.startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}

