package app.morphe.extension.pixiv.navigation

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object PersistentNavHelper {

    private const val TAG_PERSISTENT_NAV = "morphe_persistent_bottom_nav"
    const val TAB_HOME = 0
    const val TAB_SEARCH = 1
    const val TAB_NEW = 2
    const val TAB_NOTIFICATIONS = 3
    const val TAB_MYPAGE = 4

    @JvmStatic
    fun attachBottomNav(activity: Activity?) {
        attachBottomNav(activity, 0)
    }

    @JvmStatic
    fun attachBottomNav(activity: Activity?, currentTabId: Int) {
        if (activity == null) return
        try {
            val activityName = activity.javaClass.name
            // MainActivity already has native bottom navigation (id/bottom_navigation); skip injection
            if (activityName.endsWith("MainActivity") || activityName.contains(".MainActivity")) {
                return
            }

            val contentView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

            // Prevent duplicate attachments on re-start
            if (contentView.findViewWithTag<View>(TAG_PERSISTENT_NAV) != null) {
                return
            }

            // Create docked bottom navigation bar container hosting 5 standard tabs
            val navContainer = LinearLayout(activity).apply {
                tag = TAG_PERSISTENT_NAV
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0xFF1F1F1F.toInt())
                elevation = 16f

                val density = activity.resources.displayMetrics.density
                val heightPx = (56 * density).toInt()

                val frameParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    heightPx,
                    Gravity.BOTTOM
                )
                layoutParams = frameParams
            }

            val tabs = listOf(
                Pair(TAB_HOME, "Home"),
                Pair(TAB_SEARCH, "Search"),
                Pair(TAB_NEW, "New"),
                Pair(TAB_NOTIFICATIONS, "Notifications"),
                Pair(TAB_MYPAGE, "My Page")
            )

            for ((tabId, tabName) in tabs) {
                val tabView = TextView(activity).apply {
                    text = tabName
                    gravity = Gravity.CENTER
                    setTextColor(if (tabId == currentTabId) 0xFF0096FA.toInt() else 0xFFADADAD.toInt())
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f
                    )
                    setOnClickListener {
                        dispatchTabNavigation(activity, tabId)
                    }
                }
                navContainer.addView(tabView)
            }

            contentView.addView(navContainer)
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
            when (tabId) {
                TAB_HOME -> intent.putExtra("tab_action", "home")
                TAB_SEARCH -> intent.putExtra("tab_action", "search")
                TAB_NEW -> intent.putExtra("tab_action", "new")
                TAB_NOTIFICATIONS -> intent.putExtra("tab_action", "notifications")
                TAB_MYPAGE -> intent.putExtra("tab_action", "mypage")
            }
            activity.startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}


