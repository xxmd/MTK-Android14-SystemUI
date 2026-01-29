package com.aistudio.keyguard.pictorial

import android.content.Context
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.os.Bundle
import android.os.Handler
import android.os.Parcelable
import android.os.Trace
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout.LayoutParams
import android.widget.ImageView
import com.android.keyguard.KeyguardUpdateMonitor
//import com.android.systemui.res.R
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.shade.NotificationShadeWindowView
import javax.inject.Inject
import android.net.Uri
import android.content.res.Configuration
import com.android.systemui.statusbar.policy.ConfigurationController
import android.os.AsyncTask
import android.graphics.Bitmap
import com.cys.poster.PosterManager
import com.aistudio.keyguard.pictorial.PicArtUtil.SwitchChangedListeenr
import com.aistudio.keyguard.pictorial.PicArtUtil.PicDozingChangedListener
/**
 * ===aistudio PicArt update Start===
 *
 * Intercept and distribute touchEvent callback events for the NotificationPanelViewController,
 * complete up, down, left, right sliding event responses, and meet customer requirements
 *
 * @param view Side-by-side sliding with PicArt Pages using NotificationShadeWindowView
 * @param keyguardUpdateMonitor Monitor LockScreenKeyGuard display-disappear callback
 * @param screenLifecycle Monitor Screen turn on or turn off callback
 * @param mainHandler MainThread Handler
 * @param context APP Context
 *
 * ===aistudio PicArt update End===
 */

@SysUISingleton
class PicArtController
@Inject
constructor(
    private val view: NotificationShadeWindowView,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val screenLifecycle: ScreenLifecycle,
    private val configurationController: ConfigurationController,
    @Main private val mainHandler: Handler,
    context: Context,
) : BasePicArtController(mainHandler, context) {

    private var mDownX = 0f
    private var mDownY = 0f
    private var mSlidDistance = 0f
    private var mScreenWidth = 2500
    private var mContext: Context
    private var mLeftView: View? = null
    private var mLeftViewImg: ImageView? = null
    private var mRightView: View? = null
    private var mRightViewImg: ImageView? = null
    private var mLoadImageFinish: Boolean = false
    private var mImageIdRight: String? = null
    private var mTempIntercept : MovedAction = MovedAction.TOUCH_DOWN
    private var mStartAnimatorPosition = 0
    private var mImageLoadFinishCallback = ImageLoadFinishCallback()
    private var mIsDozing: Boolean = false

    private enum class ScreenState { ON, OFF }

    private var currentState: ScreenState? = null
    private var lastHandledTime = 0L
    private val DEBOUNCE_INTERVAL = 5L // ms

    private fun shouldHandle(target: ScreenState): Boolean {
        val now = System.currentTimeMillis()
        if (Math.abs(now - lastHandledTime) < DEBOUNCE_INTERVAL) return false
        if (currentState == target) return false
        lastHandledTime = now
        currentState = target
        return true
    }
    
    init {
        mContext = context
        mScreenWidth = getScreenWidth(context)
        screenLifecycle.addObserver(mScreenObserver)
        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
        configurationController.addCallback(configurationChangedListener)
        mLeftView = view.findViewById(R.id.left_view)
        mLeftViewImg  = view.findViewById(R.id.left_view_img)
        mRightView = view.findViewById(R.id.right_view)
        mRightViewImg = view.findViewById(R.id.right_view_img)
        setDefaultBg()
        applyParams()
        setListener()
    }

    private fun setListener() {
        PicArtUtil.setSwitchChangedListeenr(object : SwitchChangedListeenr {
            override fun onExploreSwitch(open: Boolean) {
                if (open) {
                    QueryDataTask().execute()
                } else {
                    setDefaultBg()
                }
            }
        })
        PicArtUtil.setPicDozingChangedListener(object : PicDozingChangedListener {
            override fun onPicDozingChanged(isDozing: Boolean) {
                mIsDozing = isDozing
                if (isDozing) {
                    if (shouldHandle(ScreenState.OFF)) {
                        Log.d(TAG, "== handleDozing (as ScreenOff) ==")
                        QueryDataTask().execute()
                    }
                } else {
                    if (shouldHandle(ScreenState.ON)) {
                        Log.d(TAG, "== handleExitDozing (as ScreenOn) ==")
                        resetViewPosition(0)
                    }
                }
            }
        })
    }

    private fun applyParams() {
        mScreenWidth = getScreenWidth(mContext)
        val layoutParamsLeft = LayoutParams(mScreenWidth, LayoutParams.MATCH_PARENT)
        layoutParamsLeft.marginStart = -mScreenWidth
        mLeftView?.layoutParams = layoutParamsLeft
        val layoutParamsRight = LayoutParams(mScreenWidth, LayoutParams.MATCH_PARENT)
        layoutParamsRight.marginStart = mScreenWidth
        mRightView?.layoutParams = layoutParamsRight
    }

    fun touchEvent(event: MotionEvent) {
        Log.d(TAG, " touchEvent:" + event.actionMasked 
            + " mIsDozing:" + mIsDozing + " mIsAvaliable:" + PicArtUtil.mIsAvaliable)
        if (!PicArtUtil.mIsAvaliable || mIsDozing) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mDownX = event.rawX
                mSlidDistance = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                mSlidDistance = event.rawX - mDownX
                mStartAnimatorPosition = if (mSlidDistance > 0) {
                    LEADING_DISTANCE - mSlidDistance.toInt()
                } else {
                    -LEADING_DISTANCE - mSlidDistance.toInt()
                }
                Log.d(TAG, "Scroll view to mSlidDistance : ${mSlidDistance.toInt()}, mStartAnimatorPosition : $mStartAnimatorPosition")
                resetViewPosition(mStartAnimatorPosition)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (mSlidDistance > mScreenWidth / 4) {
                    Log.d(TAG, "touchEvent launchDetailActivity")
                    resetViewPosition(-mScreenWidth)
                    if (isRtL()) {
                        launchPicHomeActivity(mContext, mImageIdRight)
                    } else {
                        launchDetailActivity(mContext)
                    }
                } else if (mSlidDistance < 0 && -mSlidDistance > mScreenWidth / 4) {
                    Log.d(TAG, "touchEvent launchPicHomeActivity")
                    resetViewPosition(mScreenWidth)
                    if (isRtL()) {
                        launchDetailActivity(mContext)
                    } else {
                        launchPicHomeActivity(mContext, mImageIdRight)
                    }
                } else {
                    Log.d(TAG, "touchEvent resetViewPosition")
                    resetViewPosition(0)
                    mStartAnimatorPosition = 0
                }
            }
        }
    }

    fun isRtL():Boolean {
        val config: Configuration = mContext.getResources().getConfiguration()
        if (config.getLayoutDirection() === View.LAYOUT_DIRECTION_RTL) {
            return true
        } else {
            return false
        }
    }

    /**
     * true:不能滑动，false:滑动
     */
    fun interceptTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                mDownX = event.rawX
                mDownY = event.rawY
                mTempIntercept = MovedAction.TOUCH_DOWN
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - mDownX
                val deltaY = event.rawY - mDownY
                val distance = Math.sqrt((deltaX * deltaX + deltaY * deltaY).toDouble())
                if (distance < LEADING_DISTANCE) {
                    if (mStartAnimatorPosition != 0) {
                        resetViewPosition(0)
                    }
                    return true
                }
                val atan2 = Math.atan2(Math.abs(deltaY.toDouble()),
                    Math.abs(deltaX.toDouble())) * (180 / Math.PI)
                if (atan2 in 0.0..30.0) {
                    if (mTempIntercept == MovedAction.TOUCH_DOWN)
                        mTempIntercept = MovedAction.TOUCH_HORIZONTAL
                } else {
                    if (mTempIntercept == MovedAction.TOUCH_DOWN)
                        mTempIntercept = MovedAction.TOUCH_VERTICAL
                }
                return false
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (mStartAnimatorPosition != 0) {
                    Log.d(TAG, "interceptTouchEvent ACTION_UP or ACTION_CANCEL")
                    touchEvent(event)
                    mStartAnimatorPosition = 0
                    return true
                }
            }
        }
        return false
    }

    fun dispatchTouchEvent(): MovedAction {
        return mTempIntercept
    }

    inner class QueryDataTask : AsyncTask<Void?, Void?, Bitmap?>() {
        override fun doInBackground(vararg voids: Void?): Bitmap? {
            var retBg: Bitmap? = null
            try {
                Log.i(TAG, "QueryDataTask doInBackground")
                PicArtUtil.mSlideDataInit = PosterManager.get().getPicCarouselSwitch(mContext)
                PicArtUtil.mIsAvaliable = PosterManager.get().isAvailable(mContext)
                if (!PicArtUtil.mSlideDataInit) {
                    Log.w(TAG, "QueryDataTask mSlideDataInit false")
                    return null
                }
                val bundle: Bundle = this@PicArtController.getSlideImageList(1, mContext)
                val code = bundle.getInt(CODE)
                if (code != 0) {
                    Log.w(TAG, "QueryDataTask code is not success $code")
                    return null
                }
                val msg = bundle.getString(MSG)
                Log.d(TAG, "QueryDataTask getSlideImageList code = $code msg = $msg")

                retBg = this@PicArtController.setImgBg(bundle.getParcelableArrayList(DATA));
            } catch (e: Throwable) {
                Log.e(TAG, "QueryDataTask Exception", e)
                return null
            }
            return retBg
        }

        override fun onPostExecute(bitmap: Bitmap?) {
            bitmap?.let {
                this@PicArtController.mLoadImageFinish = true
                mRightViewImg?.let { rv ->
                    this@PicArtController.loadImageResWithCallback(mContext, rv, it, mImageLoadFinishCallback)
                }
            }
            setDefaultBg()
            Log.d(TAG, "QueryDataTask onPostExecute bitmap:${bitmap}, PicArtUtil.mSlideDataInit:${PicArtUtil.mSlideDataInit}")
        }
    }

    private fun setDefaultBg() {
        if (PicArtUtil.mSlideDataInit) {
            if (PicArtUtil.isTablet(mContext)) {
                if (mContext.resources.configuration.orientation == ORIENTATION_LANDSCAPE){
                    mLeftViewImg?.setImageResource(R.drawable.picart_pad_land)
                } else{
                    mLeftViewImg?.setImageResource(R.drawable.picart_pad_port)
                }
            } else {
                mLeftViewImg?.setImageResource(R.drawable.art_default_detail)
            }
        } else {
            if (PicArtUtil.isTablet(mContext)) {
                if (mContext.resources.configuration.orientation == ORIENTATION_LANDSCAPE){
                    mLeftViewImg?.setImageResource(R.drawable.ic_pad_setting_land)
                    mRightViewImg?.setImageResource(R.drawable.ic_pad_setting_land)
                } else{
                    mLeftViewImg?.setImageResource(R.drawable.ic_pad_setting_portrait)
                    mRightViewImg?.setImageResource(R.drawable.ic_pad_setting_portrait)
                }
            } else {
                mLeftViewImg?.setImageResource(R.drawable.ic_phone_setting)
                mRightViewImg?.setImageResource(R.drawable.ic_phone_setting)
            }
        }

    }

    private fun setImgBg(data: List<Bundle>?): Bitmap? {
        var retBg: Bitmap? = null
        data?.let {
            val dataIsNotEmpty = it.isNotEmpty()
            if (dataIsNotEmpty) {
                mImageIdRight = it[0].getString(IMAGE_ID)
                val uriRight: Uri =
                    if (mContext.resources.configuration.orientation == ORIENTATION_LANDSCAPE) {
                        it[0].getParcelable<Uri>(LAND_URI)!!
                    } else {
                        it[0].getParcelable<Uri>(PORTRAIT_URI)!!
                    }
                Log.d(TAG, "GetSlideImageList urlRight = $uriRight")
                retBg = PicArtUtil.getBitmapFromUri(mContext, uriRight)
            }
        }
        return retBg
    }

    override fun resetViewPosition(x: Int) {
        Log.d(TAG, "Scroll view to positionX : $x")
        applyParams()
        view.scrollTo(x, 0)
        //Here, transition animation can be added based on the distance
        //from mStartAnimatorPosition to x use ValueAnimation
    }

    override fun resetViewPositionDelay(x: Int) {
        resetViewPosition(x)
        mLeftView?.setVisibility(View.VISIBLE)
        mRightView?.setVisibility(View.VISIBLE)
    }

    override fun initBeforeTurnOn(context: Context) {
        if (shouldHandle(ScreenState.ON)) {
            Log.d(TAG, "== handleScreenOn ==")
            resetViewPosition(0)
        }
    }

    override fun initBeforeTurnOff(context: Context) {
        if (shouldHandle(ScreenState.OFF)) {
            Log.d(TAG, "== handleScreenOff ==")
            QueryDataTask().execute()
        }
    }

    override fun keyguardVisibilityChanged(showing: Boolean) {
        Log.d(TAG, "keyguardVisibilityChanged showing = $showing, mTempIntercept=$mTempIntercept")
        if (!PicArtUtil.isTablet(mContext) && mTempIntercept == MovedAction.TOUCH_VERTICAL) {
            if (showing) {
                mLeftView?.setVisibility(View.VISIBLE)
                mRightView?.setVisibility(View.VISIBLE)
            } else {
                mLeftView?.setVisibility(View.GONE)
                mRightView?.setVisibility(View.GONE)
            }
        }
        if (!showing && mTempIntercept == MovedAction.TOUCH_VERTICAL && mContext != null) {
            PosterManager.get().startAIRouterActivity(mContext, null)
        }
    }

    override fun onConfigChangedCallback(newConfig: Configuration?) {
        if (newConfig != null) {
            Log.d(TAG, "onConfigChangedCallback orientation = ${newConfig.orientation}")
            if (PicArtUtil.isTablet(mContext)) {
                resetViewPosition(0)
                QueryDataTask().execute()
            } else {
                resetViewPosition(0)
            }
            setDefaultBg()
            mLeftView?.setVisibility(View.VISIBLE)
            mRightView?.setVisibility(View.VISIBLE)
        }
    }

    private inner class ImageLoadFinishCallback: ImageLoadedCallback {
        override fun onImageLoadedSuccess() {
            this@PicArtController.mLoadImageFinish = true
            Log.d(TAG, "ImageLoadFinishCallback Load Image Success")
        }

        override fun onImageLoadedFailed() {
            this@PicArtController.mLoadImageFinish = false
            Log.d(TAG, "ImageLoadFinishCallback Load Image Failed")
        }
    }
}