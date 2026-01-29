package com.aistudio.keyguard.pictorial

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Trace
import android.util.Log
import android.widget.ImageView
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.keyguard.ScreenLifecycle
//import com.android.systemui.res.R
import com.android.systemui.R
import com.cys.poster.PosterManager
import android.net.Uri
import android.graphics.Bitmap
import android.content.res.Configuration
import com.android.systemui.statusbar.policy.ConfigurationController
import android.util.DisplayMetrics

/**
 * ===aistudio PicArt update Start===
 *
 * @param mainHandler MainThread Handler
 * @param context APP Context
 *
 * ===aistudio PicArt update End===
 */

abstract class BasePicArtController(mainHandler: Handler, context: Context) : ScreenLifecycle.Observer{
    val mHandler = mainHandler
    enum class MovedAction {
        TOUCH_DOWN, TOUCH_HORIZONTAL, TOUCH_VERTICAL
    }

    companion object {
        const val TAG = "aistudio-PicArtController"
        const val LEADING_DISTANCE = 120
        const val CODE = "code"
        const val MSG = "msg"
        const val DATA = "data"
        const val IMAGE_ID = "imageId"
        const val URI = "uri"
        const val LAND_URI = "landUri"
        const val PORTRAIT_URI = "portraitUri"
    }

    val keyguardUpdateMonitorCallback = object : KeyguardUpdateMonitorCallback() {
        override fun onKeyguardVisibilityChanged(showing: Boolean) {
            Log.d(TAG, "onKeyguardVisibilityChanged showing = $showing")
            keyguardVisibilityChanged(showing)
            delayReset(context, 400L, showing)
        }
    }

    val mScreenObserver = object : ScreenLifecycle.Observer {
        override fun onScreenTurningOn() {
            initBeforeTurnOn(context)
        }

        override fun onScreenTurningOff() {
            resetViewPosition(0)
            initBeforeTurnOff(context)
        }
    }

    val configurationChangedListener = object : ConfigurationController.ConfigurationListener {
        override fun onConfigChanged(newConfig: Configuration?) {
            onConfigChangedCallback(newConfig)
        }
    }

    fun getScreenWidth(context: Context): Int {
        val configuration: Configuration = context.getResources().getConfiguration()
        val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val displayMetrics: DisplayMetrics = context.getResources().getDisplayMetrics()
        val width: Int = PicArtUtil.getWidth(context);
        Log.i(TAG,  "width:$width, isPortrait:$isPortrait, widthPixels:${displayMetrics.widthPixels}, heightPixels:${displayMetrics.heightPixels}");
        return width
    }

    fun getSlideImageList(size: Int, context: Context): Bundle {
        Trace.beginSection("PicArtController#getSlideImageList")
        val bundle = PosterManager.get().getSlideImageList(context, size)
        Trace.endSection()
        return bundle
    }

    fun launchDetailActivity(context: Context) {
        if (PicArtUtil.mSlideDataInit) {
            PosterManager.get().startNegativeActivity(context, null)
        } else {
            PosterManager.get().startSettingsActivity(context, null)
        }
    }

    fun launchPicHomeActivity(context: Context, imageId: String?) {
        val safeImageId = imageId ?: ""
        if (PicArtUtil.mSlideDataInit) {
            PosterManager.get().startPicActivity(context, safeImageId, null)
        } else {
            PosterManager.get().startSettingsActivity(context, null)
        }
    }

    fun delayReset(context: Context, delay: Long, showing: Boolean) {
        mHandler.postDelayed({
            resetViewPositionDelay(0)
        }, delay)
    }

    interface ImageLoadedCallback {
        fun onImageLoadedSuccess()
        fun onImageLoadedFailed()
    }

    fun loadImageResWithCallback(
        context: Context, imageView: ImageView,
        bitmap: Bitmap,
        callback: ImageLoadedCallback
    ) {
        try {
            imageView.setImageBitmap(bitmap)
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP)
            callback.onImageLoadedSuccess()
        } catch (e: java.lang.Exception) {
            callback.onImageLoadedFailed()
            Log.e(TAG, "Exception ... ", e)
        }
    }
    abstract fun keyguardVisibilityChanged(showing: Boolean)
    abstract fun resetViewPosition(x: Int)
    abstract fun resetViewPositionDelay(x: Int)
    abstract fun initBeforeTurnOn(context: Context)
    abstract fun initBeforeTurnOff(context: Context)
    abstract fun onConfigChangedCallback(newConfig: Configuration?)
}