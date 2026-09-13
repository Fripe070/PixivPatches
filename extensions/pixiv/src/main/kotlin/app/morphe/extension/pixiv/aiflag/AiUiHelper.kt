package app.morphe.extension.pixiv.aiflag

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

object AiUiHelper {

    private const val TAG_AI_BADGE = "morphe_ai_badge"

    @JvmStatic
    fun onThumbnailBound(thumbnailView: ViewGroup?, illust: Any?) {
        if (thumbnailView == null) return
        try {
            val context = thumbnailView.context
            AiDetectionHelper.appContext = context.applicationContext

            val isAi = AiDetectionHelper.isAi(illust)
            val imageView = findMainImageView(thumbnailView)

            if (isAi) {
                val hideCompletely = AiFilterConfig.getHideCompletely(context)
                if (hideCompletely) {
                    thumbnailView.visibility = View.GONE
                } else {
                    thumbnailView.visibility = View.VISIBLE
                    imageView?.alpha = 0.25f
                    showBadge(thumbnailView, context)
                }
            } else {
                thumbnailView.visibility = View.VISIBLE
                imageView?.alpha = 1.0f
                hideBadge(thumbnailView)
            }
        } catch (_: Throwable) {
            // Safety: never crash view binding
        }
    }

    @JvmStatic
    fun onDetailImageBound(viewHolder: Any?, illust: Any?) {
        // Baseline / stub for detail view floating AI badge (expanded in M2)
        try {
            if (viewHolder == null || illust == null) return
            val isAi = AiDetectionHelper.isAi(illust)
            // Implementation baseline: will be fleshed out in M2
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun onDetailBottomBarBound(view: View?, illust: Any?) {
        // Baseline / stub for detail bottom bar AI pill (expanded in M2)
        try {
            if (view == null || illust == null) return
            val isAi = AiDetectionHelper.isAi(illust)
            // Implementation baseline: will be fleshed out in M2
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun onDetailViewBound(detailContainer: ViewGroup?, illust: Any?) {
        // Contract interface helper
        try {
            if (detailContainer == null || illust == null) return
            onDetailImageBound(detailContainer, illust)
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun onDetailMetadataBound(titleView: TextView?, authorView: TextView?, illust: Any?) {
        // Contract interface helper
        try {
            if (titleView == null || illust == null) return
            onDetailBottomBarBound(titleView, illust)
        } catch (_: Throwable) {
        }
    }

    private fun findMainImageView(viewGroup: ViewGroup): ImageView? {
        val context = viewGroup.context
        val resId = context.resources.getIdentifier("image_view", "id", context.packageName)
        if (resId != 0) {
            val iv = viewGroup.findViewById<ImageView>(resId)
            if (iv != null) return iv
        }

        // Fallback: search for first suitable ImageView that is not a small icon
        var bestCandidate: ImageView? = null
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is ImageView) {
                bestCandidate = child
            } else if (child is ViewGroup) {
                val found = findMainImageView(child)
                if (found != null) return found
            }
        }
        return bestCandidate
    }

    private fun showBadge(parent: ViewGroup, context: Context) {
        var badge = parent.findViewWithTag<TextView>(TAG_AI_BADGE)
        if (badge == null) {
            badge = TextView(context).apply {
                tag = TAG_AI_BADGE
                text = "AI"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)

                val radius = dpToPx(context, 4f)
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0xFFDC2626.toInt()) // Tailwind Red-600
                    cornerRadius = radius
                }
                background = bg

                val pxH = dpToPx(context, 5f).toInt()
                val pxV = dpToPx(context, 2f).toInt()
                setPadding(pxH, pxV, pxH, pxV)

                val margin = dpToPx(context, 6f).toInt()
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    topMargin = margin
                    leftMargin = margin
                    marginStart = margin
                }
            }
            parent.addView(badge)
        }
        badge.visibility = View.VISIBLE
        badge.bringToFront()
    }

    private fun hideBadge(parent: ViewGroup) {
        val badge = parent.findViewWithTag<View>(TAG_AI_BADGE)
        badge?.visibility = View.GONE
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }
}
