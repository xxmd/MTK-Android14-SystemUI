package com.aistudio.keyguard.pictorial;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.shade.NotificationShadeWindowView;
//import com.android.systemui.res.R;
import com.android.systemui.R;
//import com.android.systemui.media.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.aistudio.keyguard.pictorial.PicArtUtil.SwitchChangedListeenr;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import java.util.List;
import java.util.concurrent.Executor;
import android.graphics.drawable.Drawable;
import javax.inject.Inject;
import android.view.View;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systub.StubUtil;

@SysUISingleton
public class PicArtSetWallpaper extends KeyguardUpdateMonitorCallback implements 
    ScreenLifecycle.Observer,
    StatusBarStateController.StateListener,
    ConfigurationController.ConfigurationListener {

    private static final String TAG = "aistudio-PicArtSetWallpaper";
    private final Context mContext;

    private final Executor mMainExecutor;

    private final ImageView mLockWallpaperView;
    private final NotificationMediaManager mNotificationMediaManager;

    private String mImageId;
    private int mOrientation;
    
    private enum ScreenState {
        OFF, ON
    }

    private ScreenState mCurrentState = null;

    private long lastHandledTime = 0;
    private static final long DEBOUNCE_INTERVAL = 5; // 防抖

    private boolean shouldHandle() {
        long now = System.currentTimeMillis();
        if (Math.abs(now - lastHandledTime) < DEBOUNCE_INTERVAL) {
            return false;
        }
        lastHandledTime = now;
        return true;
    }

    @Inject
    LockscreenWidgetController lockscreenWidgetController;
    private boolean mIskeyguardVisibility = true;

    @Inject
    public PicArtSetWallpaper(
            Context context,
            @Main Executor mainExecutor,
            @NonNull NotificationShadeWindowView shadeWindowView,
            @NonNull ScreenLifecycle screenLifecycle,
            @NonNull StatusBarStateController statusBarStateController,
            @NonNull ConfigurationController configurationController,
            @NonNull NotificationMediaManager notificationMediaManager) {
        mContext = context;
        mLockWallpaperView = shadeWindowView.findViewById(R.id.backdrop_back);
        mNotificationMediaManager = notificationMediaManager;
        
        statusBarStateController.addCallback(this);
        screenLifecycle.addObserver(this);
        configurationController.addCallback(this);
        mMainExecutor = mainExecutor;
        StubUtil.registerUserSwitchReceiver(context);
    }

    @Override
    public void onKeyguardVisibilityChanged(boolean showing) {
        super.onKeyguardVisibilityChanged(showing);
        mIskeyguardVisibility = showing;
    }

    @Override
    public void onScreenTurnedOn() {
        Log.d(TAG, "onScreenTurnedOn mImageId = " + mImageId + " mIskeyguardVisibility:" + mIskeyguardVisibility);
        if (!shouldHandle()) return;
        if (mCurrentState == ScreenState.ON) return;
        mCurrentState = ScreenState.ON;
        handleScreenOn();
    }

    @Override
    public void onScreenTurnedOff () {
        Log.d(TAG, "onScreenTurnedOff mImageId = " + mImageId + " mIskeyguardVisibility:" + mIskeyguardVisibility);
        if (!shouldHandle()) return;
        if (mCurrentState == ScreenState.OFF) return;
        mCurrentState = ScreenState.OFF;
        handleScreenOff();
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        Log.d(TAG, "onDozingChanged isDozing：" + isDozing);
        if (!shouldHandle()) return;
        ScreenState target = isDozing ? ScreenState.OFF : ScreenState.ON;
        if (mCurrentState == target) return;
        mCurrentState = target;

        if (isDozing) {
            // true = 进入 Doze 模式（AOD 或部分息屏）
            handleScreenOff();
        } else {
            // false = 退出 Doze 模式（亮屏）
            handleScreenOn();
        }
        PicArtUtil.notifyPicDozingChanged(isDozing);
    }

    private void handleScreenOn() {
        if (PicArtUtil.isInit(mContext) && !TextUtils.isEmpty(mImageId)) {
            PicArtUtil.execute(new Runnable() {
                @Override
                public void run() {
                    if (PicArtUtil.isPidChanged(mContext)) {
                        lockscreenWidgetController.createWidget(mImageId);
                    }
                }
            });
            PicArtUtil.dot(mContext, mImageId);
        }
    }

    private void handleScreenOff() {
        try {
            new QueryDataTask().execute(true);
        } catch (Throwable e) {
            Log.e(TAG, "onScreenTurnedOff", e);
        }
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        if (!mIskeyguardVisibility) {
            return;
        }
        mOrientation = newConfig.orientation;
        Drawable retBg = mOrientation == Configuration.ORIENTATION_LANDSCAPE ? PicArtUtil.landDrawable : PicArtUtil.portDrawable;
        if (PicArtUtil.isInit(mContext) && retBg != null) {
            setWallpaper(mContext, retBg);
        }
    }

    class QueryDataTask extends AsyncTask<Boolean, Void, Drawable> {
        private boolean isSetBg = false;

        @Override
        protected Drawable doInBackground(Boolean... isSetBg) {
            Drawable retBg = null;
            try {
                
                PicArtUtil.dotAvailable(mContext);
                // 判断是否传入了参数，并且参数数组不为空
                if (isSetBg != null && isSetBg.length > 0) {
                    this.isSetBg = isSetBg[0];  // 获取传递的第一个布尔值
                }

                if (!PicArtUtil.getSwitch(mContext)) {
                    Log.d(TAG, "QueryDataTask getSwitch false");
                    resetImgId();
                    return null;
                }

                List<KeyguardPicArtBean> bean = PicArtUtil.getScreenImageList(mContext);
                if (bean == null || bean.size() == 0) {
                    return null;
                }
                Log.d(TAG, "QueryDataTask query imgList = " + bean.toString());
                mImageId = bean.get(0).getImgId();
                if (PicArtUtil.isTablet(mContext)){
                    Uri landUri = bean.get(0).getLandUri();
                    Uri portUri = bean.get(0).getPortUri();
                    if (landUri != null) PicArtUtil.landDrawable = PicArtUtil.getBitmapFromUriStr(mContext, landUri.toString());
                    if (portUri != null) PicArtUtil.portDrawable = PicArtUtil.getBitmapFromUriStr(mContext, portUri.toString());
                    if (mContext.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        retBg = PicArtUtil.landDrawable;
                        PicArtUtil.bgDrawable = PicArtUtil.landDrawable;
                    } else {
                        retBg = PicArtUtil.portDrawable;
                        PicArtUtil.bgDrawable = PicArtUtil.portDrawable;
                    }
                } else {
                    Uri uri = bean.get(0).getUri();
                    if (uri != null){
                        PicArtUtil.bgDrawable = PicArtUtil.getBitmapFromUriStr(mContext, uri.toString());
                    }
                    PicArtUtil.landDrawable = PicArtUtil.bgDrawable;
                    PicArtUtil.portDrawable = PicArtUtil.bgDrawable;
                    retBg = PicArtUtil.bgDrawable;
                }
            } catch (Throwable e) {
                Log.e(TAG, "QueryDataTask Exception",e);
                return null;
            } finally {
                lockscreenWidgetController.createWidget(mImageId);
            }
            return retBg;
        }

        @Override
        protected void onPostExecute(Drawable drawable) {
            Log.d(TAG, "onPostExecute " + drawable + " isSetBg:" + isSetBg);
            if (isSetBg && drawable != null) {
                setWallpaper(mContext, drawable);
            }
        }

        private void resetImgId() {
            mImageId = "";
            PicArtUtil.bgDrawable = null;
            PicArtUtil.landDrawable = null;
            PicArtUtil.portDrawable = null;
        }

    }

    /**
     * set wallpaper
     *
     * @param context
     * @param drawable
     */
    @SuppressLint("WrongConstant")
    private void setWallpaper(Context context, Drawable drawable) {
        try {
            mLockWallpaperView.setImageDrawable(drawable);
            mLockWallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } catch (Exception e) {
            Log.e(TAG, "Exception ... ", e);
        }
    }
}
