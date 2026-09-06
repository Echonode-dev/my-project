package com.unhook.app.overlay;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.unhook.app.interstitial.InterstitialActivity;

import java.lang.ref.WeakReference;

/**
 * Interception choreography (ARCHITECTURE.md §5):
 *
 *   1. COVER (~200 ms): a non-focusable TYPE_APPLICATION_OVERLAY window hides
 *      the trigger app's first frames. Not focusable = we never steal input;
 *      it IS touch-blocking so the habit tap can't land.
 *   2. INTERSTITIAL: full-screen activity. Holding SYSTEM_ALERT_WINDOW is the
 *      documented exemption that lets this background activity launch work
 *      on API 29+.
 *   3. The interstitial removes the cover in its onResume(); a fallback
 *      timer removes it anyway if the activity fails to start (OEM quirks).
 *
 * Every call is defensive: overlay permission may have been revoked, some
 * vendor skins throw BadTokenException — the user must never crash because
 * of our courtesy curtain.
 */
public final class InterceptionController {

    private static final long COVER_FALLBACK_MS = 1500L;

    private static WeakReference<View> coverRef;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable fallbackRemover = new Runnable() {
        @Override
        public void run() {
            removeCover(null);
        }
    };

    private InterceptionController() {
    }

    public static void show(@NonNull Context appContext, @NonNull String pkg,
                            @NonNull String mode, @NonNull String reason,
                            double prob, int opensToday, int typicalHour,
                            double[] features) {
        Intent intent = new Intent(appContext, InterstitialActivity.class);
        intent.putExtra(InterstitialActivity.EXTRA_PKG, pkg);
        intent.putExtra(InterstitialActivity.EXTRA_MODE, mode);
        intent.putExtra(InterstitialActivity.EXTRA_REASON, reason);
        intent.putExtra(InterstitialActivity.EXTRA_PROB, prob);
        intent.putExtra(InterstitialActivity.EXTRA_OPENS_TODAY, opensToday);
        intent.putExtra(InterstitialActivity.EXTRA_TYPICAL_HOUR, typicalHour);
        intent.putExtra(InterstitialActivity.EXTRA_FEATURES, features);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);

        addCover(appContext);
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    appContext.startActivity(intent);
                } catch (Exception ignored) {
                    // Background-launch blocked by some skins: degrade quietly.
                }
            }
        }, 220L);
        MAIN.postDelayed(fallbackRemover, COVER_FALLBACK_MS);
    }

    private static void addCover(Context appContext) {
        if (!Settings.canDrawOverlays(appContext)) {
            return; // permission revoked: interstitial activity may still work
        }
        try {
            WindowManager wm = (WindowManager)
                    appContext.getSystemService(Context.WINDOW_SERVICE);
            View cover = new View(appContext);
            cover.setBackgroundColor(0xEE0B4F4A); // deep teal, 93% opaque
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            wm.addView(cover, lp);
            coverRef = new WeakReference<>(cover);
        } catch (Exception ignored) {
            coverRef = null;
        }
    }

    /** Called from InterstitialActivity.onResume() and the fallback timer. */
    public static void removeCover(Context anyContext) {
        MAIN.removeCallbacks(fallbackRemover);
        View cover = coverRef == null ? null : coverRef.get();
        coverRef = null;
        if (cover == null) {
            return;
        }
        try {
            WindowManager wm = (WindowManager)
                    cover.getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(cover);
        } catch (Exception ignored) {
        }
    }
}
