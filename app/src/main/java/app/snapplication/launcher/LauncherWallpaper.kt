package app.snapplication.launcher

import android.app.Activity
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.concurrent.Executors

/** Places a user-selected wallpaper behind MainActivity; portrait and landscape use centre cropping without stretching. */
object LauncherWallpaper {
    /** Loads a bounded image off the UI thread and releases it when this screen is detached. */
    fun wrap(activity: Activity, content: View): View {
        val saved = activity.getSharedPreferences("launcher", 0).getString("wallpaperUri", null) ?: return content
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val shell = FrameLayout(activity).apply {
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(content, FrameLayout.LayoutParams(-1, -1))
        }
        var detached = false
        var bitmap: android.graphics.Bitmap? = null
        val executor = Executors.newSingleThreadExecutor()
        shell.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            /** Starts decoding only when this wallpaper becomes visible. */
            override fun onViewAttachedToWindow(v: View) {
                if (detached) return
                executor.execute {
                    val decoded = runCatching {
                        val uri = Uri.parse(saved)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
                        activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    }.getOrNull()
                    activity.runOnUiThread {
                        if (detached || activity.isDestroyed) decoded?.recycle()
                        else { bitmap = decoded; image.setImageBitmap(decoded) }
                    }
                }
            }
            /** Prevents old screens from retaining wallpaper memory or updating a new screen. */
            override fun onViewDetachedFromWindow(v: View) {
                detached = true; executor.shutdownNow()
                image.setImageDrawable(null); bitmap?.recycle(); bitmap = null
            }
        })
        return shell
    }
}
