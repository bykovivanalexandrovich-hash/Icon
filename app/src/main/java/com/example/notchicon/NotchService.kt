package com.example.notchicon

import android.accessibilityservice.AccessibilityService
import android.content.ContentResolver
import android.database.ContentObserver
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.DisplayCutout
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView

class NotchService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastPkg: String? = null
    private var lastId = -1L
    private var overlay: View? = null
    private val hideRunnable = Runnable { removeOverlay() }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            checkScreenshot()
        }
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        if (p == packageName || p == "com.android.systemui" || p.contains("inputmethod")) return
        lastPkg = p
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        removeOverlay()
        super.onDestroy()
    }

    private fun checkScreenshot() {
        val args = Bundle().apply {
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Images.Media.DATE_ADDED)
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH,
                    MediaStore.Images.Media.DATE_ADDED
                ),
                args, null
            )?.use { c ->
                if (!c.moveToFirst()) return
                val id = c.getLong(0)
                if (id == lastId) return
                val age = System.currentTimeMillis() / 1000 - c.getLong(3)
                if (age > 10) return
                val s = (c.getString(1) ?: "") + (c.getString(2) ?: "")
                val isShot = s.contains("screenshot", true) ||
                    s.contains("скриншот", true) || s.contains("снимок", true)
                if (!isShot) return
                lastId = id
                lastPkg?.let { show(it) }
            }
        } catch (e: Exception) {
        }
    }

    private fun show(pkg: String) {
        removeOverlay()
        val icon = try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            return
        }
        val img = ImageView(this).apply {
            setImageDrawable(icon)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        val root = FrameLayout(this)
        root.addView(img)
        root.setOnApplyWindowInsetsListener { _, insets ->
            place(img, insets.displayCutout)
            insets
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        try {
            wm.addView(root, lp)
            overlay = root
            handler.postDelayed(hideRunnable, 2500)
        } catch (e: Exception) {
        }
    }

    private fun place(img: ImageView, cutout: DisplayCutout?) {
        val dm = resources.displayMetrics
        val rect: Rect = cutout?.boundingRects?.minByOrNull { it.top }
            ?: Rect(
                dm.widthPixels / 2 - (12 * dm.density).toInt(), 0,
                dm.widthPixels / 2 + (12 * dm.density).toInt(), (24 * dm.density).toInt()
            )
        val size = maxOf(minOf(rect.width(), rect.height()), (16 * dm.density).toInt())
        img.layoutParams = FrameLayout.LayoutParams(size, size).apply {
            leftMargin = rect.centerX() - size / 2
            topMargin = rect.centerY() - size / 2
        }
    }

    private fun removeOverlay() {
        handler.removeCallbacks(hideRunnable)
        overlay?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        overlay = null
    }
}
