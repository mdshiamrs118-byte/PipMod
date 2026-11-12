package com.pipmod.app;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Our own two-finger pinch-to-resize for the PiP window.
 * It does not depend on the (missing/disabled) pinch code in AOSP 11: it listens to the
 * touch events that PipTouchHandler receives, runs a ScaleGestureDetector on them and resizes the
 * PiP through the same PipTaskOrganizer calls the built-in drag-resize uses.
 */
class PinchController {

    private final Object touch;            // PipTouchHandler instance
    private final XSharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ScaleGestureDetector detector;

    private boolean enabled = true;
    private boolean active;                // we own the current touch stream
    private boolean scaling;
    private float scale = 1f;
    private int minPct = 30, maxPct = 90;
    private final Rect start = new Rect();
    private final Rect last = new Rect();
    private boolean loggedApply;

    PinchController(Object touch, XSharedPreferences prefs) {
        this.touch = touch;
        this.prefs = prefs;
    }

    Object owner() {
        return touch;
    }

    /** @return true if the event was consumed by the pinch gesture. */
    boolean handle(MotionEvent ev, XC_MethodHook.MethodHookParam p) {
        try {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                prefs.reload();
                enabled = prefs.getBoolean("pinch_enabled", true);
                minPct = prefs.getInt("min_percent", 30);
                maxPct = prefs.getInt("max_percent", 90);
                active = false;
                scaling = false;
            }
            if (!enabled) return false;

            if (!active && action == MotionEvent.ACTION_POINTER_DOWN && ev.getPointerCount() == 2) {
                active = true;
                MainHook.log("pinch: second finger down, taking over touch stream");
                // Cancel whatever the normal PiP touch handler started with the first finger.
                try {
                    MotionEvent c = MotionEvent.obtain(ev);
                    c.setAction(MotionEvent.ACTION_CANCEL);
                    XposedBridge.invokeOriginalMethod(p.method, p.thisObject, new Object[]{c});
                    c.recycle();
                } catch (Throwable t) {
                    MainHook.log("pinch: cancel failed " + t);
                }
            }

            if (!active) {
                // Let the detector see the first finger so it has context.
                if (action == MotionEvent.ACTION_DOWN) det().onTouchEvent(ev);
                return false;
            }

            det().onTouchEvent(ev);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (scaling) finish();
                scaling = false;
                active = false;
            }
            return true;
        } catch (Throwable t) {
            MainHook.log("pinch: handle failed " + t);
            active = false;
            return false;
        }
    }

    private ScaleGestureDetector det() {
        if (detector == null) {
            Context ctx = (Context) getObj(touch, "mContext");
            if (ctx == null) {
                ctx = (Context) XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentApplication");
            }
            detector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.OnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector d) {
                    Rect b = currentBounds();
                    if (b == null || b.isEmpty()) {
                        MainHook.log("pinch: could not read PiP bounds");
                        return false;
                    }
                    start.set(b);
                    last.set(b);
                    scale = 1f;
                    scaling = true;
                    MainHook.log("pinch: begin, start bounds " + b);
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    if (!scaling) return false;
                    scale *= d.getScaleFactor();
                    last.set(compute());
                    applyDuring();
                    return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector d) {
                    if (scaling) finish();
                    scaling = false;
                }
            });
            try {
                detector.setQuickScaleEnabled(false);
            } catch (Throwable ignored) {
            }
        }
        return detector;
    }

    // ------------------------------------------------------------ geometry
    private Rect compute() {
        DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
        int w0 = dm.widthPixels, h0 = dm.heightPixels;
        int base = Math.min(w0, h0);
        float minPx = base * minPct / 100f;
        float maxPx = Math.max(minPx, base * maxPct / 100f);
        float sw = start.width(), sh = start.height();
        float shortEdge = Math.min(sw, sh);

        float f = scale;
        f = Math.max(f, minPx / shortEdge);
        f = Math.min(f, maxPx / shortEdge);
        f = Math.min(f, w0 / sw);
        f = Math.min(f, h0 / sh);
        f = Math.max(f, 0.1f);
        scale = f; // don't accumulate past the limits

        int w = Math.round(sw * f);
        int h = Math.round(sh * f);
        int left = start.centerX() - w / 2;
        int top = start.centerY() - h / 2;
        left = Math.max(0, Math.min(left, Math.max(0, w0 - w)));
        top = Math.max(0, Math.min(top, Math.max(0, h0 - h)));
        return new Rect(left, top, left + w, top + h);
    }

    private Rect currentBounds() {
        try {
            Object motion = getObj(touch, "mMotionHelper");
            Object r = XposedHelpers.callMethod(motion, "getBounds");
            return r instanceof Rect ? new Rect((Rect) r) : null;
        } catch (Throwable t) {
            MainHook.log("pinch: getBounds failed " + t);
            return null;
        }
    }

    // ------------------------------------------------------------ applying
    private Object resizeHandler() {
        return getObj(touch, "mPipResizeGestureHandler");
    }

    private Object organizer() {
        Object rh = resizeHandler();
        Object o = rh != null ? getObj(rh, "mPipTaskOrganizer") : null;
        return o != null ? o : getObj(touch, "mPipTaskOrganizer");
    }

    private void applyDuring() {
        try {
            Object org = organizer();
            if (org == null) return;
            Method m = findMethod(org.getClass(), "scheduleUserResizePip", 3);
            if (m == null) {
                if (!loggedApply) MainHook.log("pinch: scheduleUserResizePip not found (will resize at end)");
                loggedApply = true;
                return;
            }
            m.setAccessible(true);
            m.invoke(org, new Rect(start), new Rect(last), null);
            if (!loggedApply) MainHook.log("pinch: live resize via scheduleUserResizePip");
            loggedApply = true;
        } catch (Throwable t) {
            if (!loggedApply) MainHook.log("pinch: live resize failed " + t);
            loggedApply = true;
        }
    }

    private void finish() {
        final Rect to = new Rect(last);
        MainHook.log("pinch: end, final bounds " + to);
        final Object rh = resizeHandler();
        final Object motion = getObj(touch, "mMotionHelper");
        try {
            if (rh != null) {
                Object ub = getObj(rh, "mUserResizeBounds");
                if (ub instanceof Rect) ((Rect) ub).set(to);
                Object lb = getObj(rh, "mLastResizeBounds");
                if (lb instanceof Rect) ((Rect) lb).set(to);
            }
        } catch (Throwable ignored) {
        }

        final Runnable after = new Runnable() {
            @Override
            public void run() {
                try {
                    XposedHelpers.callMethod(motion, "synchronizePinnedStackBounds");
                } catch (Throwable ignored) {
                }
                try {
                    Object r = rh != null ? getObj(rh, "mUpdateMovementBoundsRunnable") : null;
                    if (r instanceof Runnable) ((Runnable) r).run();
                } catch (Throwable ignored) {
                }
            }
        };

        try {
            Object org = organizer();
            Method m = org == null ? null : findMethod(org.getClass(), "scheduleFinishResizePip", 2);
            if (m != null) {
                m.setAccessible(true);
                Consumer<Rect> cb = new Consumer<Rect>() {
                    @Override
                    public void accept(Rect r) {
                        main.post(after);
                    }
                };
                m.invoke(org, to, cb);
                MainHook.log("pinch: finished via scheduleFinishResizePip");
                return;
            }
        } catch (Throwable t) {
            MainHook.log("pinch: scheduleFinishResizePip failed " + t);
        }
        // Fallback: animate with the motion helper.
        try {
            XposedHelpers.callMethod(motion, "resizeAndAnimatePipUnchecked", to, 200);
            main.postDelayed(after, 300);
            MainHook.log("pinch: finished via resizeAndAnimatePipUnchecked");
        } catch (Throwable t) {
            MainHook.log("pinch: all apply methods failed " + t);
        }
    }

    // ------------------------------------------------------------ reflection helpers
    private static Object getObj(Object o, String name) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name, int params) {
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == params) return m;
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
