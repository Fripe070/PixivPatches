package app.morphe.extension.pixiv.viewer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object EnhancedViewerHelper {

    private const val TAG_LOADING_BADGE = "morphe_hd_loading_badge"
    private const val TAG_ATTACHED_ZOOM = 0x7f099991
    private const val TAG_ATTACHED_DISMISS = 0x7f099992

    // Memory cache for standard-resolution artwork bitmaps
    private val placeholderCache = LruCache<String, Bitmap>(50)
    private var lastDetailBitmap: Bitmap? = null
    private var lastDetailWorkId: Long = 0L

    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Tracks saved user matrices during full-res load: target identity hashCode -> Matrix
    private val savedMatrices = HashMap<Int, Matrix>()

    // ------------------------------------------------------------------------
    // 1. In-Place Detail Page Quick-Peek Zoom (Instagram Style)
    // ------------------------------------------------------------------------

    @JvmStatic
    fun onDetailImageBound(viewHolder: Any?, illust: Any?) {
        try {
            if (viewHolder == null || illust == null) return

            val itemView = getFieldValue(viewHolder, "itemView") as? View
                ?: (viewHolder as? View) ?: return
            val context = itemView.context ?: return

            val resId = context.resources.getIdentifier("image_view", "id", context.packageName)
            val imageView = (if (resId != 0) itemView.findViewById<ImageView>(resId) else null)
                ?: (getFieldValue(viewHolder, "imageView") as? ImageView)
                ?: return

            // Cache standard bitmap for instant placeholder in fullscreen
            cacheDetailBitmap(illust, imageView)

            if (imageView.getTag(TAG_ATTACHED_ZOOM) == true) return
            imageView.setTag(TAG_ATTACHED_ZOOM, true)

            attachQuickPeekZoom(imageView, itemView)
        } catch (_: Throwable) {
        }
    }

    private fun cacheDetailBitmap(illust: Any, imageView: ImageView) {
        try {
            val workId = runCatching {
                illust.javaClass.getMethod("getId").invoke(illust) as? Long
            }.getOrNull() ?: 0L

            val url = getStandardImageUrl(illust, 0)
            imageView.post {
                val d = imageView.drawable
                if (d is BitmapDrawable && d.bitmap != null && !d.bitmap.isRecycled) {
                    val bmp = d.bitmap
                    if (url != null) placeholderCache.put(url, bmp)
                    lastDetailBitmap = bmp
                    lastDetailWorkId = workId
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun attachQuickPeekZoom(targetView: ImageView, containerView: View) {
        val context = targetView.context ?: return
        var scaleFactor = 1.0f
        var focusX = 0f
        var focusY = 0f
        var isPinching = false
        var activePointerId = MotionEvent.INVALID_POINTER_ID
        var lastTouchX = 0f
        var lastTouchY = 0f
        var transX = 0f
        var transY = 0f

        val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = detector.scaleFactor
                scaleFactor *= scale
                scaleFactor = scaleFactor.coerceIn(1.0f, 4.5f)

                targetView.scaleX = scaleFactor
                targetView.scaleY = scaleFactor

                focusX = detector.focusX
                focusY = detector.focusY
                targetView.pivotX = focusX
                targetView.pivotY = focusY
                return true
            }

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isPinching = true
                containerView.parent?.requestDisallowInterceptTouchEvent(true)
                targetView.elevation = dpToPx(context, 16f)
                (targetView.parent as? ViewGroup)?.clipChildren = false
                return true
            }
        })

        targetView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPinching && event.pointerCount >= 2) {
                        containerView.parent?.requestDisallowInterceptTouchEvent(true)
                        val idx = event.findPointerIndex(activePointerId)
                        if (idx != -1) {
                            val x = event.getX(idx)
                            val y = event.getY(idx)
                            val dx = x - lastTouchX
                            val dy = y - lastTouchY
                            transX += dx
                            transY += dy
                            targetView.translationX = transX
                            targetView.translationY = transY
                            lastTouchX = x
                            lastTouchY = y
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount <= 2 && isPinching) {
                        resetQuickPeek(targetView, containerView) {
                            scaleFactor = 1.0f
                            transX = 0f
                            transY = 0f
                            isPinching = false
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isPinching) {
                        resetQuickPeek(targetView, containerView) {
                            scaleFactor = 1.0f
                            transX = 0f
                            transY = 0f
                            isPinching = false
                        }
                        return@setOnTouchListener true
                    }
                }
            }

            if (isPinching) true else false
        }
    }

    private fun resetQuickPeek(
        targetView: ImageView,
        containerView: View,
        onComplete: () -> Unit
    ) {
        val startScale = targetView.scaleX
        val startTransX = targetView.translationX
        val startTransY = targetView.translationY

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                targetView.scaleX = startScale + (1.0f - startScale) * fraction
                targetView.scaleY = startScale + (1.0f - startScale) * fraction
                targetView.translationX = startTransX * (1.0f - fraction)
                targetView.translationY = startTransY * (1.0f - fraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    targetView.scaleX = 1.0f
                    targetView.scaleY = 1.0f
                    targetView.translationX = 0f
                    targetView.translationY = 0f
                    targetView.elevation = 0f
                    containerView.parent?.requestDisallowInterceptTouchEvent(false)
                    onComplete()
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------------------
    // 2. Fullscreen Instant Placeholder & Discreet Loading Badge
    // ------------------------------------------------------------------------

    @JvmStatic
    fun onFullScreenItemCreated(mm5View: Any?, photoAttacher: Any?) {
        try {
            val itemView = mm5View as? ViewGroup ?: return
            val context = itemView.context ?: return
            val activity = getActivity(context) ?: return

            val photoViewResId = context.resources.getIdentifier("photo_view", "id", context.packageName)
            val imageView = (if (photoViewResId != 0) itemView.findViewById<ImageView>(photoViewResId) else null)
                ?: getChildImageView(itemView) ?: return

            val progressResId = context.resources.getIdentifier("progress_bar", "id", context.packageName)
            val defaultProgressBar = if (progressResId != 0) itemView.findViewById<View>(progressResId) else null

            // Hide the default center spinner
            defaultProgressBar?.visibility = View.GONE

            // Inject the discreet corner loading badge
            setupDiscreetLoadingBadge(itemView, context)

            val pageIndex = (imageView.tag as? Int) ?: 0
            val intent = activity.intent
            val illust = intent?.getParcelableExtra<android.os.Parcelable>("KEY_ILLUST")
                ?: intent?.extras?.get("KEY_ILLUST")

            val placeholderUrl = if (illust != null) getStandardImageUrl(illust, pageIndex) else null

            // Instant memory cache hit check
            var bmp: Bitmap? = if (placeholderUrl != null) placeholderCache.get(placeholderUrl) else null
            if (bmp == null && pageIndex == 0 && lastDetailBitmap != null && !lastDetailBitmap!!.isRecycled) {
                bmp = lastDetailBitmap
            }

            if (bmp != null) {
                applyPlaceholder(imageView, photoAttacher, bmp)
            } else if (!placeholderUrl.isNullOrEmpty()) {
                fetchPlaceholderAsync(placeholderUrl, imageView, photoAttacher)
            }
        } catch (_: Throwable) {
        }
    }

    private fun applyPlaceholder(imageView: ImageView, photoAttacher: Any?, bitmap: Bitmap) {
        mainHandler.post {
            try {
                if (imageView.drawable == null) {
                    imageView.setImageBitmap(bitmap)
                    updatePhotoAttacher(photoAttacher, imageView.drawable)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun updatePhotoAttacher(photoAttacher: Any?, drawable: Drawable?) {
        if (photoAttacher == null || drawable == null) return
        try {
            val fMethod = photoAttacher.javaClass.getMethod("f", Drawable::class.java)
            fMethod.invoke(photoAttacher, drawable)
        } catch (_: Throwable) {
        }
    }

    private fun setupDiscreetLoadingBadge(container: ViewGroup, context: Context) {
        if (container.findViewWithTag<View>(TAG_LOADING_BADGE) != null) return

        val badge = LinearLayout(context).apply {
            tag = TAG_LOADING_BADGE
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dpToPx(context, 10f).toInt(),
                dpToPx(context, 6f).toInt(),
                dpToPx(context, 10f).toInt(),
                dpToPx(context, 6f).toInt()
            )
            background = GradientDrawable().apply {
                setColor(Color.argb(175, 20, 20, 20))
                cornerRadius = dpToPx(context, 16f)
                setStroke(dpToPx(context, 1f).toInt(), Color.argb(80, 255, 255, 255))
            }
            elevation = dpToPx(context, 8f)

            val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                val size = dpToPx(context, 14f).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = dpToPx(context, 6f).toInt()
                }
            }

            val label = TextView(context).apply {
                text = "HD"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }

            addView(spinner)
            addView(label)

            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dpToPx(context, 56f).toInt()
                rightMargin = dpToPx(context, 16f).toInt()
            }
        }

        container.addView(badge)
    }

    private fun fetchPlaceholderAsync(url: String, imageView: ImageView, photoAttacher: Any?) {
        executor.execute {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Referer", "https://app-api.pixiv.net/")
                    setRequestProperty("User-Agent", "PixivAndroidApp/6.196.0 (Android 15; Pixel 8)")
                    connectTimeout = 5000
                    readTimeout = 8000
                }
                if (conn.responseCode in 200..299) {
                    val bmp = BitmapFactory.decodeStream(conn.inputStream)
                    conn.disconnect()
                    if (bmp != null) {
                        placeholderCache.put(url, bmp)
                        applyPlaceholder(imageView, photoAttacher, bmp)
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------------
    // 3. Full-Res Swap & Matrix Preservation
    // ------------------------------------------------------------------------

    @JvmStatic
    fun onFullResLoaded(yr4Target: Any?) {
        try {
            if (yr4Target == null) return
            val attacher = getFieldValue(yr4Target, "f")
            val mm5Layout = getFieldValue(yr4Target, "e") as? ViewGroup

            // Capture current user zoom scale and matrix BEFORE Glide sets the new full-res drawable
            var savedMatrix: Matrix? = null
            if (attacher != null) {
                val currentScale = runCatching {
                    attacher.javaClass.getMethod("d").invoke(attacher) as? Float
                }.getOrNull() ?: 1.0f

                val userMatrix = getFieldValue(attacher, "m") as? Matrix
                if (userMatrix != null && currentScale > 1.05f) {
                    savedMatrix = Matrix(userMatrix)
                }
            }

            // Post to mainHandler so it executes right after yr4.d finishes setting the full-res drawable
            mainHandler.post {
                try {
                    // 1. Fade out the HD loading badge
                    if (mm5Layout != null) {
                        val badge = mm5Layout.findViewWithTag<View>(TAG_LOADING_BADGE)
                        if (badge != null && badge.visibility == View.VISIBLE) {
                            badge.animate()
                                .alpha(0f)
                                .setDuration(250)
                                .withEndAction { badge.visibility = View.GONE }
                                .start()
                        }
                    }

                    // 2. Restore user's zoom matrix seamlessly
                    if (attacher != null && savedMatrix != null) {
                        val mField = attacher.javaClass.getDeclaredField("m").apply { isAccessible = true }
                        val currentM = mField.get(attacher) as? Matrix
                        if (currentM != null) {
                            currentM.set(savedMatrix)
                            val aMethod = attacher.javaClass.getDeclaredMethod("a").apply { isAccessible = true }
                            aMethod.invoke(attacher)

                            val imageView = getFieldValue(attacher, "h") as? ImageView
                            val cMethod = attacher.javaClass.getDeclaredMethod("c").apply { isAccessible = true }
                            val displayMatrix = cMethod.invoke(attacher) as? Matrix
                            if (imageView != null && displayMatrix != null) {
                                imageView.imageMatrix = displayMatrix
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------------
    // 4. Swipe-Down to Dismiss Fullscreen Viewer
    // ------------------------------------------------------------------------

    @JvmStatic
    fun onFullScreenCreated(activity: Activity?) {
        try {
            if (activity == null) return
            val window = activity.window ?: return

            if (activity.window.decorView.getTag(TAG_ATTACHED_DISMISS) == true) return
            activity.window.decorView.setTag(TAG_ATTACHED_DISMISS, true)

            val originalCallback = window.callback ?: return
            val contentView = activity.findViewById<View>(android.R.id.content) ?: return
            val screenHeight = activity.resources.displayMetrics.heightPixels.toFloat()

            var initialY = 0f
            var initialX = 0f
            var isDismissing = false
            var isEligible = false

            val proxy = Proxy.newProxyInstance(
                window.javaClass.classLoader,
                arrayOf(Window.Callback::class.java),
                object : InvocationHandler {
                    override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
                        if (method.name == "dispatchTouchEvent" && args != null && args.isNotEmpty()) {
                            val event = args[0] as? MotionEvent
                            if (event != null) {
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        initialX = event.rawX
                                        initialY = event.rawY
                                        isDismissing = false
                                        isEligible = isCurrentPageAtBaseZoom(activity)
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        if (isEligible && event.pointerCount == 1) {
                                            val dy = event.rawY - initialY
                                            val dx = event.rawX - initialX

                                            if (dy > 35f && dy > Math.abs(dx) * 1.3f) {
                                                isDismissing = true
                                                val dragY = dy - 35f
                                                contentView.translationY = dragY

                                                val progress = (dragY / (screenHeight * 0.5f)).coerceIn(0f, 1f)
                                                val scale = 1f - (progress * 0.12f)
                                                contentView.scaleX = scale
                                                contentView.scaleY = scale
                                                activity.window.decorView.alpha = 1f - (progress * 0.5f)
                                                return true
                                            }
                                        }
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        if (isDismissing) {
                                            val currentTransY = contentView.translationY
                                            val dismissThreshold = dpToPx(activity, 130f)

                                            if (currentTransY > dismissThreshold) {
                                                contentView.animate()
                                                    .translationY(screenHeight)
                                                    .alpha(0f)
                                                    .setDuration(160)
                                                    .setInterpolator(DecelerateInterpolator())
                                                    .withEndAction {
                                                        activity.finish()
                                                        activity.overridePendingTransition(0, 0)
                                                    }
                                                    .start()
                                            } else {
                                                contentView.animate()
                                                    .translationY(0f)
                                                    .scaleX(1f)
                                                    .scaleY(1f)
                                                    .setDuration(180)
                                                    .setInterpolator(DecelerateInterpolator())
                                                    .start()
                                                activity.window.decorView.animate()
                                                    .alpha(1f)
                                                    .setDuration(180)
                                                    .start()
                                            }
                                            isDismissing = false
                                            isEligible = false
                                            return true
                                        }
                                    }
                                }
                            }
                        }

                        // Forward all other callback calls
                        return if (args != null) {
                            method.invoke(originalCallback, *args)
                        } else {
                            method.invoke(originalCallback)
                        }
                    }
                }
            ) as Window.Callback

            window.callback = proxy
        } catch (_: Throwable) {
        }
    }

    private fun isCurrentPageAtBaseZoom(activity: Activity): Boolean {
        return try {
            val viewPagerResId = activity.resources.getIdentifier("illust_view_pager", "id", activity.packageName)
            val viewPager = (if (viewPagerResId != 0) activity.findViewById<View>(viewPagerResId) else null) as? ViewGroup
                ?: return true

            val currentItem = runCatching {
                viewPager.javaClass.getMethod("getCurrentItem").invoke(viewPager) as? Int
            }.getOrNull() ?: 0

            var currentImageView: ImageView? = null
            for (i in 0 until viewPager.childCount) {
                val child = viewPager.getChildAt(i)
                val iv = getChildImageView(child as? ViewGroup)
                if (iv != null && (iv.tag as? Int) == currentItem) {
                    currentImageView = iv
                    break
                }
            }

            if (currentImageView == null) return true

            val onTouchListener = getFieldValue(currentImageView, "mOnTouchListener")
                ?: getFieldValue(currentImageView, "onTouchListener")
            if (onTouchListener != null) {
                val scale = runCatching {
                    onTouchListener.javaClass.getMethod("d").invoke(onTouchListener) as? Float
                }.getOrNull() ?: 1.0f
                scale <= 1.05f
            } else {
                true
            }
        } catch (_: Throwable) {
            true
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private fun getStandardImageUrl(illust: Any, pageIndex: Int): String? {
        try {
            val pageCount = runCatching {
                illust.javaClass.getField("pageCount").getInt(illust)
            }.getOrDefault(1)

            if (pageCount <= 1 || pageIndex == 0) {
                val imageUrlsObj = runCatching {
                    illust.javaClass.getMethod("getImageUrls").invoke(illust)
                }.getOrNull() ?: runCatching {
                    illust.javaClass.getField("imageUrls").get(illust)
                }.getOrNull()

                if (imageUrlsObj != null) {
                    val large = runCatching { imageUrlsObj.javaClass.getMethod("getLarge").invoke(imageUrlsObj) as? String }.getOrNull()
                    if (!large.isNullOrEmpty()) return large
                    val medium = runCatching { imageUrlsObj.javaClass.getMethod("getMedium").invoke(imageUrlsObj) as? String }.getOrNull()
                    if (!medium.isNullOrEmpty()) return medium
                }
            }

            val metaPages = runCatching {
                val field = illust.javaClass.getField("metaPages")
                field.get(illust) as? List<*>
            }.getOrNull()

            if (metaPages != null && pageIndex in metaPages.indices) {
                val page = metaPages[pageIndex]
                if (page != null) {
                    val urls = runCatching { page.javaClass.getMethod("getImageUrls").invoke(page) }.getOrNull()
                    if (urls != null) {
                        val large = runCatching { urls.javaClass.getMethod("getLarge").invoke(urls) as? String }.getOrNull()
                        if (!large.isNullOrEmpty()) return large
                        val medium = runCatching { urls.javaClass.getMethod("getMedium").invoke(urls) as? String }.getOrNull()
                        if (!medium.isNullOrEmpty()) return medium
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun getChildImageView(viewGroup: ViewGroup?): ImageView? {
        if (viewGroup == null) return null
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is ImageView) return child
            if (child is ViewGroup) {
                val nested = getChildImageView(child)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun getFieldValue(target: Any, fieldName: String): Any? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(target)
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        return null
    }

    private fun getActivity(context: Context?): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }
}
