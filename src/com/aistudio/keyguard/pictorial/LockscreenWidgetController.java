package com.aistudio.keyguard.pictorial;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.palette.graphics.Palette;
import static com.aistudio.keyguard.pictorial.PicArtUtil.getProperty;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
//import com.android.systemui.res.R;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.NotificationPanelView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.Utils;
import com.cys.poster.Constant;
import com.cys.poster.PosterManager;
import com.cys.poster.RetConst;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.shade.NotificationShadeWindowView;
import android.widget.ImageView;
import java.util.List;

import javax.inject.Inject;
import android.text.TextUtils;
import com.android.systemui.statusbar.policy.SplitShadeStateController;
import android.database.ContentObserver;
import android.net.Uri;
import com.cys.poster.DualArrowAnimator;

@SysUISingleton
public class LockscreenWidgetController extends KeyguardUpdateMonitorCallback implements
        StatusBarStateController.StateListener,
        ConfigurationController.ConfigurationListener,
        KeyguardStateController.Callback,
        ScreenLifecycle.Observer {

    private static final String TAG = "aistudio-Widget";

    private boolean picSwtich = true;
    private boolean widgetSwtich = true;
    private final Context mContext;
    private final FrameLayout frameLayout;
    private boolean mIskeyguardVisibility;
    private String imageId;
    private KeyguardStateController mKeyguardStateController;
    @NonNull private StatusBarStateController mStatusBarStateController;
    private Bundle bundle;
    private View mView;
    private Handler mHandler;
    private SplitShadeStateController mSplitShadeStateController;
    private final ImageView mLockWallpaperView;
    private MyObserver observer;
    private boolean isScreenOff = false;
    private DualArrowAnimator mDualArrowAnimator;
    private boolean animationSwitch = false;
    private boolean isBouncerShowing = false;
    private boolean mIsDozing = false;
    private float widgetAlpha = 1f;

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
    public LockscreenWidgetController(
            Context context,
            @NonNull NotificationPanelView panelView,
            @NonNull NotificationShadeWindowView shadeWindowView,
            @NonNull StatusBarStateController statusBarStateController,
            @NonNull ConfigurationController configurationController,
            @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
            @NonNull KeyguardStateController keyguardStateController,
            SplitShadeStateController splitShadeStateController,
            @NonNull ScreenLifecycle screenLifecycle) {
        super();
        mContext = context;
        mLockWallpaperView = shadeWindowView.findViewById(R.id.backdrop_back);
        mSplitShadeStateController = splitShadeStateController;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        frameLayout = panelView.findViewById(R.id.lock_screen_widget_view);
        keyguardStateController.addCallback(this);
        configurationController.addCallback(this);
        statusBarStateController.addCallback(this);
        keyguardUpdateMonitor.registerCallback(this);
        screenLifecycle.addObserver(this);
        mHandler = new Handler(Looper.getMainLooper());
        mDualArrowAnimator = new DualArrowAnimator(panelView.findViewById(R.id.start_arrow), 
            panelView.findViewById(R.id.end_arrow));
    }

    @Override
    public void onScreenTurnedOff() {
        Log.d(TAG, "onScreenTurnedOff");
        if (!shouldHandle()) return;
        if (mCurrentState == ScreenState.OFF) return;
        mCurrentState = ScreenState.OFF;
        processTurnOff();
    }

    @Override
    public void onScreenTurnedOn() {
        Log.d(TAG, "onScreenTurnedOn");
        if (!shouldHandle()) return;
        if (mCurrentState == ScreenState.ON) return;
        mCurrentState = ScreenState.ON;
        processTurnedOn();
    }

    @Override
    public void onStateChanged(int newState) {
        Log.d(TAG, "onStateChanged newState = " + newState);
        initObserver();
        updateWidgetKeyguardVisibility(2);
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
         Log.d(TAG, "onDozingChanged isDozing：" + isDozing);
        mIsDozing = isDozing;
        if (!shouldHandle()) return;
        ScreenState target = isDozing ? ScreenState.OFF : ScreenState.ON;
        if (mCurrentState == target) return;
        mCurrentState = target;
         if (isDozing) {
            // true = 进入 Doze 模式（AOD 或部分息屏）
            processTurnOff();
         } else {
            // false = 退出 Doze 模式（亮屏）
            processTurnedOn();
         }
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        if (mIskeyguardVisibility && mDualArrowAnimator != null && animationSwitch && bundle != null) {
            mDualArrowAnimator.updateMarginTop(getMarginTop() - 100, getLeftArrow(mContext),
                                            getRightArrow(mContext));
        }
        applyParams();
    }

    @Override
    public void onKeyguardBouncerFullyShowingChanged(boolean bouncerIsOrWillBeShowing) {
        super.onKeyguardBouncerFullyShowingChanged(bouncerIsOrWillBeShowing);
        Log.d(TAG,"onKeyguardBouncerFullyShowingChanged == " + bouncerIsOrWillBeShowing);
        isBouncerShowing = bouncerIsOrWillBeShowing;
        if (bouncerIsOrWillBeShowing) {
            if (frameLayout != null) {
                frameLayout.setVisibility(View.GONE);
            }
        } else {
            updateWidgetKeyguardVisibility(1);
        }
    }

    @Override
    public void onUnlockedChanged() {
        Log.d(TAG, "onUnlockedChanged");
        updateWidgetKeyguardVisibility(2);
    }

    @Override
    public void onKeyguardShowingChanged() {
        Log.d(TAG, "onKeyguardShowingChanged");
        updateWidgetKeyguardVisibility(2);
    }

    @Override
    public void onKeyguardFadingAwayChanged() {
        Log.d(TAG, "onKeyguardFadingAwayChanged");
        updateWidgetKeyguardVisibility(2);
    }

    @Override
    public void onKeyguardVisibilityChanged(boolean visible) {
        Log.d(TAG, "onKeyguardVisibilityChanged,visible:" + visible);
        mIskeyguardVisibility = visible;
        updateWidgetKeyguardVisibility(2);
        if (!mIskeyguardVisibility && mDualArrowAnimator != null && animationSwitch) {
            mDualArrowAnimator.cancelSafe();
        }
    }

    private int getLeftArrow(Context context) {
        return isRTL(context) ? R.drawable.ic_end_arrow : R.drawable.ic_start_arrow;
    }

    private int getRightArrow(Context context) {
        return isRTL(context) ? R.drawable.ic_start_arrow : R.drawable.ic_end_arrow;
    }

    private boolean isRTL(Context context) {
        Configuration config = context.getResources().getConfiguration();
        if (config == null) {
            return false;
        }
        return (config.screenLayout & Configuration.SCREENLAYOUT_LAYOUTDIR_MASK)
                == Configuration.SCREENLAYOUT_LAYOUTDIR_RTL;
    }

    public void createWidget(String imageId) {
        try {
            if (!widgetSwtich) {
                Log.w(TAG, "createWidget widgetSwtich is false");
                return;
            }
            PosterManager.get().destroyWidget(mContext);
            PosterManager.get().createWidget(mContext, Constant.VALUE_NORMAL,
                    imageId, Color.WHITE, view -> initWidgetView(view));
        } catch (Throwable e) {
            Log.e(TAG, "createWidget Exception, imageId:" + imageId, e);
        }
    }

    /**
     * Set the alpha of this view.
     */
    public void setAlpha(float alpha) {
        widgetAlpha = alpha;
        if (widgetSwtich) {
            if (frameLayout != null && frameLayout.getVisibility() == View.VISIBLE) {
                frameLayout.setAlpha(alpha);
            }
            int statusBarState = mStatusBarStateController.getState();
            // Log.i(TAG, "setAlpha " + alpha + " mIsDozing:" + mIsDozing 
            //     + " statusBarState:" + statusBarState + " isBouncerShowing:" + isBouncerShowing);
            if (statusBarState == StatusBarState.KEYGUARD) {
                if (frameLayout != null) {
                    if (alpha < 0.1 || mIsDozing || isBouncerShowing) {
                        frameLayout.setVisibility(View.GONE);
                    } else {
                        frameLayout.setVisibility(View.VISIBLE);
                        frameLayout.setAlpha(alpha);
                    }
                }
            }
        }
        if (alpha < 0.1 && mDualArrowAnimator != null
             && animationSwitch && mDualArrowAnimator.isRunning()) {
            mDualArrowAnimator.cancelSafe();
        }
    }

    public boolean isAlphaZ() {
        Log.i(TAG, "isAlphaZ widgetAlpha:" + widgetAlpha);
        return widgetAlpha <= 0f;
    }

    private void processTurnedOn() {
        isScreenOff = false;
        addWidgetView(mView);
        if (mIskeyguardVisibility && mDualArrowAnimator != null && animationSwitch && bundle != null) {
            mDualArrowAnimator.updateMarginTop(getMarginTop() - 100, getLeftArrow(mContext),
                                            getRightArrow(mContext));
            mDualArrowAnimator.startSafe();
        }
    }

    private void processTurnOff() {
        isScreenOff = true;
        removeWidgetView();
        updateWidgetKeyguardVisibility(2);
        if (mDualArrowAnimator != null) {
            mDualArrowAnimator.cancelSafe();
        }
        PicArtUtil.execute(new Runnable() {
                @Override
                public void run() {
                    widgetSwtich = PosterManager.get().getPicWidgetSwitch(mContext);
                    picSwtich = PicArtUtil.getSwitch(mContext);
                    Bundle ret = PosterManager.get().getAnimationCondition(mContext);
                    if (ret != null) {
                        animationSwitch = ret.getBoolean(Constant.KEY_PIC_SWITCH);
                        if (bundle == null) {
                            bundle = ret;
                        }
                    }
                }
        });
    }

    /**
     * 刷新哪一个 0:widget, 1:keyguard, 其他:widget + keyguard
     * @param whichRefresh
     */
    private void updateWidgetKeyguardVisibility(int whichRefresh) {
        boolean isKeyguardShowing = isKeyguardShowingVisibility();
        if (whichRefresh == 0) {
            updateWidgetVisibility(isKeyguardShowing);
        } else if (whichRefresh == 1) {
            updateWidgetVisibility(isKeyguardShowing);
            updateKeyguardVisibility(isKeyguardShowing);
        } else {
            updateWidgetVisibility(isKeyguardShowing);
            updateKeyguardVisibility(isKeyguardShowing);
        }
    }

    private void updateWidgetVisibility(boolean isKeyguardShowing) {
        try {
            if (frameLayout != null) {
                int statusBarState = mStatusBarStateController.getState();
                if (isKeyguardShowing && widgetSwtich && statusBarState == StatusBarState.KEYGUARD) {
                    if (isBouncerShowing || mIsDozing) {
                        frameLayout.setVisibility(View.GONE);
                    } else {
                        frameLayout.setVisibility(View.VISIBLE);
                        frameLayout.setAlpha(1f);
                    }
                    if (mView != null && frameLayout != null && frameLayout.getChildCount() == 0 && !isScreenOff) {
                        Log.i(TAG, "LockWidgetView addWidgetView updateWidgetVisibility");
                        frameLayout.addView(mView);
                    }
                } else {
                    frameLayout.setVisibility(View.GONE);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "updateWidgetVisibility Exception", e);
        }
    }

    private void updateKeyguardVisibility(boolean isKeyguardShowing) {
        if (mLockWallpaperView != null) {
            int statusBarState = mStatusBarStateController.getState();
            if (isKeyguardShowing && picSwtich && (statusBarState == StatusBarState.KEYGUARD || statusBarState == StatusBarState.SHADE_LOCKED)) {
                mLockWallpaperView.setVisibility(View.VISIBLE);
            } else {
                mLockWallpaperView.setVisibility(View.GONE);
            }
        }
    }

    /**
     * keyguard 展示且可见且状态是keyguard
     * @return
     */
    private boolean isKeyguardShowingVisibility() {
        boolean isKeyguardShowing = mKeyguardStateController.isShowing()
                && !mKeyguardStateController.isKeyguardGoingAway();       
        Log.d(TAG, "isKeyguardShowing:" + isKeyguardShowing + " mIskeyguardVisibility:" + mIskeyguardVisibility
                + " picSwtich:" + picSwtich + " widgetSwtich:" + widgetSwtich);
        return mIskeyguardVisibility && isKeyguardShowing;
    }

    private void initWidgetView(View view) {
        if (!widgetSwtich) {
            Log.w(TAG, "LockWidgetView initWidgetView widgetSwtich is false");
            return;
        }
        Log.i(TAG, "LockWidgetView initWidgetView " + view);
        bundle = view != null ? (Bundle) view.getTag() : null;
        mView = view;
        if (!isScreenOff) {
            addWidgetView(view);
        }
    }

    /**
     * removeWidgetView view
     */
    private void removeWidgetView() {
        if (mHandler == null) {
            Log.e(TAG, "LockWidgetView removeWidgetView mHandler is null");
            return;
        }
        mHandler.post(() -> {
            try {
                Log.i(TAG, "LockWidgetView removeWidgetView ");
                if (frameLayout != null && frameLayout.getChildCount() > 0) {
                    frameLayout.removeAllViews();
                }
            } catch (Throwable e) {
                Log.e(TAG, "LockWidgetView removeWidgetView exception", e);
            }
        });
    }

    /**
     * addWidgetView view
     */
    private void addWidgetView(View view) {
        if (mHandler == null) {
            Log.e(TAG, "LockWidgetView add mHandler is null");
            return;
        }
        if (!widgetSwtich) {
            Log.w(TAG, "LockWidgetView widgetSwtich is false");
            return;
        }
        mHandler.post(() -> {
            try {
                Log.i(TAG, "LockWidgetView add: " + view  + " mIskeyguardVisibility:" + mIskeyguardVisibility);
                applyParams();
                if (frameLayout != null && frameLayout.getChildCount() > 0) {
                    frameLayout.removeAllViews();
                }
                if (view != null && frameLayout != null) {
                    frameLayout.addView(view);
                }
                updateWidgetKeyguardVisibility(0);
            } catch (Throwable e) {
                Log.e(TAG, "LockWidgetView add: exception", e);
            }
        });
    }

    private void applyParams() {
        if (frameLayout == null) {
            return;
        }
        FrameLayout.LayoutParams layoutParam = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        boolean split = isSplit();
        boolean isTablet = PicArtUtil.isTablet(mContext);
        boolean isLeft = mContext.getResources().getBoolean(R.bool.config_pad_lockscreen_widget_at_left);
        int sidePadding = mContext.getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        int marginHorizontal = 0;
        int screenHeight = PicArtUtil.getHeight(mContext);
        int marginStart = 0;
        int marginEnd = 0;
        int marginTop = 0;
        if (isTablet) {
            // 平板
            marginTop = screenHeight*7/10 + 15;
            if (split) {
                //分屏
                if (!isLeft) {
                    marginStart = mContext.getResources().getDisplayMetrics().widthPixels / 2 + marginHorizontal;
                    marginEnd = sidePadding + marginHorizontal;
                } else {
                    marginStart = sidePadding + marginHorizontal;
                    marginEnd = mContext.getResources().getDisplayMetrics().widthPixels / 2 + sidePadding + marginHorizontal;
                }
            } else {
                //整屏
                marginStart = sidePadding + marginHorizontal;
                marginEnd =  sidePadding + marginHorizontal;
            }
        } else {
            // 手机
            marginStart = sidePadding + marginHorizontal;
            marginEnd =  sidePadding + marginHorizontal;
            marginTop = screenHeight - mContext.getResources().getDimensionPixelSize(com.android.systemui.customization.R.dimen.lock_icon_margin_bottom) - 50;
        }
        marginStart = getMarginStart(marginStart);
        marginEnd = getMarginEnd(marginEnd);
        marginTop = getMarginTop(marginTop);
        Log.d(TAG, "applyParams split = " + split + " isTablet = " + isTablet
                + " marginStart = " + marginStart + " marginTop = " + marginTop
                + " marginEnd = " + marginEnd + " model = " + Device.getModel());
        layoutParam.setMarginStart(marginStart);
        layoutParam.setMarginEnd(marginEnd);
        layoutParam.topMargin = marginTop;
        frameLayout.setLayoutParams(layoutParam);
    }

    private int getMarginTop() {
        int screenHeight = PicArtUtil.getHeight(mContext);
        boolean isTablet = PicArtUtil.isTablet(mContext);
        int marginTop = 0;
        if (isTablet) {
            // 平板
            marginTop = screenHeight*7/10 + 15;
        } else {
            // 手机
            marginTop = screenHeight - mContext.getResources().getDimensionPixelSize(com.android.systemui.customization.R.dimen.lock_icon_margin_bottom) - 50;
        }
        marginTop = getMarginTop(marginTop);
        return marginTop;
    }
    
    private int getMarginTop(int defValue) {
        return getMargin("debug.widget.margin.top", defValue);
    }

    private int getMarginStart(int defValue) {
        return getMargin("debug.widget.margin.start", defValue);
    }

    private int getMarginEnd(int defValue) {
        return getMargin("debug.widget.margin.end", defValue);
    }

    private int getMargin(String key, int defValue) {
        String margin = getProperty(key, "");
        if (TextUtils.isEmpty(margin)) {
            return getDeviceMargin(key, defValue);
        }
        int ret = str2Int(margin);
        if (ret < 0) {
            return getDeviceMargin(key, defValue);
        }
        return ret;
    }

    private int getDeviceMargin(String key, int defValue) {
        if (bundle == null) {
            return defValue;
        }
        int ret = bundle.getInt(Device.getModel() + getKey(key), 0);
        if (ret <= 0) {
            return defValue;
        } else {
            return ret;
        }
    }

    private String getKey(String key) {
        if ("debug.widget.margin.top".equals(key)) {
            return "_top" + getOrientationKey();
        } else if ("debug.widget.margin.start".equals(key)) {
            return "_start" + getOrientationKey();
        } else if ("debug.widget.margin.end".equals(key)) {
            return "_end" + getOrientationKey();
        } else {
            return "";
        }
    }

    private String getOrientationKey() {
        if (mContext.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return "_land" + getPcKey();
        } else {
            return "_portrait";
        }
    }

    private boolean isLand() {
        try {
            if (mContext.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return true;
            } else {
                return false;
            }
        } catch (Throwable e) {
            Log.e(TAG, "isLand Exception", e);
            return false;
        }
    }
    private boolean isSplit() {
        try {
            int split = -1;
            if (bundle != null) {
                split = bundle.getInt("split", -1);
            }
            if (split > -1) {
                return split > 0 && isLand();
            }
            return mSplitShadeStateController.shouldUseSplitNotificationShade(mContext.getResources()) && isLand();
        } catch(Throwable e) {
            Log.e(TAG, "isSplit Exception", e);
        }
        return false;
    }
    private String getPcKey() {
        return PicArtUtil.isPcMode(mContext) ? "_pc":"";
    }

    private int str2Int(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Throwable e) {
            return -1;
        }
    }

    private void initObserver() {
        try {
            if (observer == null && mContext != null) {
                observer = new MyObserver(null);
                PosterManager.get().registerObserver(mContext,
                        Constant.OBSERVER_NOTIFY_PICTORIAL_SWITCH_URI, observer);
                PosterManager.get().registerObserver(mContext,
                        Constant.OBSERVER_NOTIFY_WIDGET_SWITCH_URI, observer);
                PosterManager.get().registerObserver(mContext,
                        Constant.OBSERVER_NOTIFY_CAROUSEL_SWITCH_URI, observer);
            }
        } catch (Throwable e) {
            observer = null;
            Log.e(TAG, "initObserver Exception", e);
        }
    }

    private void uninitObserver() {
        try {
            if (observer != null && mContext != null) {
                PosterManager.get().unRegisterObserver(mContext, observer);
            }
        } catch (Throwable e) {
            Log.e(TAG, "uninitObserver Exception", e);
        }
    }


    class MyObserver extends ContentObserver {

        /**
         * Creates a content observer.
         *
         * @param handler The handler to run {@link #onChange} on, or null if none.
         */
        public MyObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (mContext != null) {
                if (Constant.OBSERVER_NOTIFY_PICTORIAL_SWITCH_URI.toString().equals(uri.toString())) {
                    picSwtich = PicArtUtil.getSwitch(mContext);
                    widgetSwtich = picSwtich;
                    Log.i(TAG, "ContentObserver getSwitch:" + picSwtich);
                } else if (Constant.OBSERVER_NOTIFY_WIDGET_SWITCH_URI.toString().equals(uri.toString())) {
                    widgetSwtich = PosterManager.get().getPicWidgetSwitch(mContext);
                    Log.i(TAG, "ContentObserver getPicWidgetSwitch:" + widgetSwtich);
                } else if (Constant.OBSERVER_NOTIFY_CAROUSEL_SWITCH_URI.toString().equals(uri.toString())) {
                    PicArtUtil.mSlideDataInit = PosterManager.get().getPicCarouselSwitch(mContext);
                    if (mHandler != null) {
                        mHandler.post(() -> {
                            PicArtUtil.notifyExploreSwtichChanged(PicArtUtil.mSlideDataInit);
                        });   
                    }
                    Log.i(TAG, "ContentObserver getPicCarouselSwitch:" + PicArtUtil.mSlideDataInit);
                } else {
                    Log.i(TAG, "ContentObserver selfChange:" + selfChange + " uri: "  + (uri != null? uri.toString():""));
                }
            }
        }
    }
}
