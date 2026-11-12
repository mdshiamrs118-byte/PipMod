package com.pipmod.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks SystemUI's PiP classes on Android 11.
 * Everything is wrapped in try/catch and logged to the LSPosed log AND
 * sent to the app (button "Show debug info").
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[PipMod] ";
    private static final String PKG = "com.pipmod.app";

    private static final String[] SURFACE_HELPER = {
            "com.android.systemui.pip.PipSurfaceTransactionHelper",
            "com.android.wm.shell.pip.PipSurfaceTransactionHelper"};
    private static final String[] BOUNDS_HANDLER = {
            "com.android.systemui.pip.PipBoundsHandler",
            "com.android.wm.shell.pip.PipBoundsHandler"};
    private static final String[] SNAP_ALGORITHM = {
            "com.android.systemui.pip.PipSnapAlgorithm",
            "com.android.wm.shell.pip.PipSnapAlgorithm"};
    private static final String[] RESIZE_HANDLER = {
            "com.android.systemui.pip.phone.PipResizeGestureHandler",
            "com.android.wm.shell.pip.phone.PipResizeGestureHandler"};
    private static final String[] TOUCH_HANDLER = {
            "com.android.systemui.pip.phone.PipTouchHandler",
            "com.android.wm.shell.pip.phone.PipTouchHandler"};

    private XSharedPreferences prefs;

    private XSharedPreferences prefs() {
        if (prefs == null) {
            prefs = new XSharedPreferences(PKG, "settings");
        }
        prefs.reload();
        return prefs;
    }

    private static int screenWidthPx() {
        return Resources.getSystem().getDisplayMetrics().widthPixels;
    }

    private static float density() {
        return Resources.getSystem().getDisplayMetrics().density;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"com.android.systemui".equals(lpp.packageName)) return;
        log("v1.3 loaded into SystemUI");

        try {
            XSharedPreferences p = prefs();
            log("Prefs file: " + p.getFile() + " readable=" + p.getFile().canRead()
                    + " corner_dp=" + p.getInt("corner_dp", -1)
                    + " default_percent=" + p.getInt("default_percent", -1)
                    + " min_percent=" + p.getInt("min_percent", -1)
                    + " max_percent=" + p.getInt("max_percent", -1));
        } catch (Throwable t) {
            logErr("prefs check", t);
        }

        hookCornerRadius(lpp);
        hookPinch(lpp);
        hookDefaultSize(lpp);
    }

    // ---------------------------------------------------------------- corner radius
    private void hookCornerRadius(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> c = findFirst(lpp, SURFACE_HELPER);
            if (c == null) {
                log("PipSurfaceTransactionHelper NOT FOUND");
                return;
            }
            dump(c);

            Set<?> hooked = XposedBridge.hookAllMethods(c, "round", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    setCornerField(p.thisObject);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length >= 3 && p.args[2] instanceof Boolean
                                && (Boolean) p.args[2]) {
                            float px = prefs().getInt("corner_dp", 16) * density();
                            XposedHelpers.callMethod(p.args[0], "setCornerRadius", p.args[1], px);
                        }
                    } catch (Throwable t) {
                        logErr("round/after", t);
                    }
                }
            });
            log("round() hooks: " + hooked.size());
        } catch (Throwable t) {
            logErr("corner radius", t);
        }
    }

    private void setCornerField(Object helper) {
        try {
            float px = prefs().getInt("corner_dp", 16) * density();
            if (!setNumber(helper, "mCornerRadius", px)) {
                for (Field f : helper.getClass().getDeclaredFields()) {
                    if (f.getName().toLowerCase().contains("corner")) {
                        setNumber(helper, f.getName(), px);
                    }
                }
            }
        } catch (Throwable t) {
            logErr("setCornerField", t);
        }
    }

    // ---------------------------------------------------------------- pinch zoom (own implementation)
    private PinchController pinch;

    private void hookPinch(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> rh = findFirst(lpp, RESIZE_HANDLER);
            if (rh != null) dump(rh);

            Class<?> touch = findFirst(lpp, TOUCH_HANDLER);
            if (touch == null) {
                log("PipTouchHandler NOT FOUND - pinch unavailable");
                return;
            }
            dump(touch);

            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length != 1 || !(p.args[0] instanceof MotionEvent)) return;
                        if (pinch == null || pinch.owner() != p.thisObject) {
                            pinch = new PinchController(p.thisObject, prefs());
                        }
                        if (pinch.handle((MotionEvent) p.args[0], p)) {
                            Class<?> rt = ((Method) p.method).getReturnType();
                            p.setResult(rt == boolean.class ? (Object) Boolean.TRUE : null);
                        }
                    } catch (Throwable t) {
                        logErr("pinch hook", t);
                    }
                }
            };
            int n = 0;
            for (Method m : touch.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                boolean named = m.getName().equals("handleTouchEvent");
                boolean shape = pt.length == 1 && pt[0] == InputEvent.class
                        && m.getReturnType() == boolean.class;
                if (named || shape) {
                    XposedBridge.hookMethod(m, h);
                    n++;
                    log("pinch: hooked " + m);
                }
            }
            log("pinch: PipTouchHandler touch hooks = " + n);
        } catch (Throwable t) {
            logErr("hookPinch", t);
        }
    }

    // ---------------------------------------------------------------- default size
    /**
     * On Android 11 the default PiP size comes from PipSnapAlgorithm:
     * minSize = max(minEdge, smallestDisplaySide * mDefaultSizePercent).
     */
    private void hookDefaultSize(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> snap = findFirst(lpp, SNAP_ALGORITHM);
            if (snap != null) {
                dump(snap);
                XC_MethodHook h = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        setDefaultSize(p.thisObject);
                        flush();
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        setDefaultSize(p.thisObject);
                    }
                };
                XposedBridge.hookAllConstructors(snap, h);
                int n = 0;
                for (Method m : snap.getDeclaredMethods()) {
                    String name = m.getName().toLowerCase();
                    if (name.contains("size") || name.contains("reload")) {
                        XposedBridge.hookMethod(m, h);
                        n++;
                    }
                }
                log("PipSnapAlgorithm hooks: " + n);
            } else {
                log("PipSnapAlgorithm NOT FOUND");
            }

            Class<?> c = findFirst(lpp, BOUNDS_HANDLER);
            if (c != null) {
                dump(c);
                XC_MethodHook h2 = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        setDefaultSize(p.thisObject);
                    }
                };
                XposedBridge.hookAllConstructors(c, h2);
                XposedBridge.hookAllMethods(c, "reloadResources", h2);
            } else {
                log("PipBoundsHandler NOT FOUND");
            }
        } catch (Throwable t) {
            logErr("default size", t);
        }
    }

    private void setDefaultSize(Object obj) {
        try {
            float pct = prefs().getInt("default_percent", 50) / 100f;
            setNumber(obj, "mDefaultSizePercent", pct);
        } catch (Throwable t) {
            logErr("setDefaultSize", t);
        }
    }

    // ---------------------------------------------------------------- helpers
    private static Class<?> findFirst(XC_LoadPackage.LoadPackageParam lpp, String[] names) {
        for (String n : names) {
            Class<?> c = XposedHelpers.findClassIfExists(n, lpp.classLoader);
            if (c != null) {
                log("Found class " + n);
                return c;
            }
        }
        return null;
    }

    private static Field findField(Class<?> c, String name) {
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** Sets a numeric field regardless of whether it is int/float/double/long. */
    private static boolean setNumber(Object o, String name, double v) {
        try {
            Field f = findField(o.getClass(), name);
            if (f == null) return false;
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == float.class) f.setFloat(o, (float) v);
            else if (t == int.class) f.setInt(o, (int) Math.round(v));
            else if (t == double.class) f.setDouble(o, v);
            else if (t == long.class) f.setLong(o, Math.round(v));
            else return false;
            return true;
        } catch (Throwable t) {
            logErr("setNumber " + name, t);
            return false;
        }
    }

    private static void dump(Class<?> c) {
        try {
            StringBuilder sb = new StringBuilder("Dump " + c.getName() + "\n  fields:");
            for (Field f : c.getDeclaredFields()) {
                sb.append(' ').append(f.getType().getSimpleName()).append(' ').append(f.getName()).append(';');
            }
            sb.append("\n  methods:");
            for (Method m : c.getDeclaredMethods()) {
                sb.append(' ').append(m.getName()).append('(');
                Class<?>[] pt = m.getParameterTypes();
                for (int i = 0; i < pt.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(pt[i].getSimpleName());
                }
                sb.append(");");
            }
            log(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- logging / reporting
    private static final StringBuilder REPORT = new StringBuilder();
    private static boolean dirty;
    private static long lastFlush;

    static synchronized void log(String s) {
        XposedBridge.log(TAG + s);
        if (REPORT.length() > 150000) REPORT.delete(0, 50000);
        REPORT.append(s).append("\n\n");
        dirty = true;
        flush();
    }

    private static void logErr(String what, Throwable t) {
        log("Failed: " + what + " -> " + t);
    }

    /** Sends the report to the module app (shown under "Show debug info"). */
    private static synchronized void flush() {
        try {
            if (!dirty) return;
            long now = System.currentTimeMillis();
            if (now - lastFlush < 1500) return;
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (!(app instanceof Context)) return;
            lastFlush = now;
            dirty = false;
            Intent i = new Intent("com.pipmod.app.DEBUG");
            i.setComponent(new ComponentName(PKG, PKG + ".DebugReceiver"));
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            i.putExtra("text", REPORT.toString());
            ((Context) app).sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}
