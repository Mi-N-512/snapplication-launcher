package app.snapplication.launcher

import android.app.Activity
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.concurrent.Executors

/** Places a user-selected wallpaper behind MainActivity; portrait and landscape use centre cropping without stretching. */
object LauncherWallpaper {
    /** Loads a bounded image off the UI thread and releases it when this screen is detached. */
    fun wrap(activity: Activity, content: View): View {
        val saved = activity.getSharedPreferences("launcher", 0).getString("wallpaperUri", null)
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
                        if (saved != null) decodeImage(activity, Uri.parse(saved)) else systemImage(activity)
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

    /** Reads a chosen image with its camera rotation, then lets the measured screen crop it without stretching. */
    private fun decodeImage(activity: Activity, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
        val decoded = activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = runCatching {
            activity.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull()
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return decoded
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (error: Exception) { decoded.recycle(); throw error }
    }

    /** Crops a readable static system wallpaper locally; protected or live wallpapers remain Android-owned. */
    @android.annotation.SuppressLint("MissingPermission") // Optional read; denial is caught by the caller without requesting broad storage access.
    @Suppress("DEPRECATION")
    private fun systemImage(activity: Activity): Bitmap? {
        val manager = WallpaperManager.getInstance(activity)
        if (manager.wallpaperInfo != null) return null
        // Some Android versions deny wallpaper reads. The transparent shell then keeps the system
        // wallpaper visible; choosing an image in launcher settings always supports local cropping.
        val drawable = manager.peekDrawable() ?: return null
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        if (width <= 0 || height <= 0) return null
        val scale = minOf(1f, 2048f / maxOf(width, height))
        val bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val oldBounds = android.graphics.Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
        } catch (error: Exception) { bitmap.recycle(); throw error }
        finally { drawable.bounds = oldBounds }
        return bitmap
    }
}
