package com.pipmod.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Point;

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
        log("v1.2 loaded into SystemUI");

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
        hookPinchFlag(lpp);
        hookResizeHandler(lpp);
        hookDefaultSize(lpp);

        Class<?> touch = findFirst(lpp, TOUCH_HANDLER);
        if (touch != null) dump(touch);
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

    // ---------------------------------------------------------------- pinch flag
    private void hookPinchFlag(XC_LoadPackage.LoadPackageParam lpp) {
        XC_MethodHook flagHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    for (Object a : p.args) {
                        if ("pip_pinch_resize".equals(a)) {
                            log("flag pip_pinch_resize queried -> forcing true");
                            if (prefs().getBoolean("pinch_enabled", true)) p.setResult(true);
                            return;
                        }
                    }
                } catch (Throwable t) {
                    logErr("flag", t);
                }
            }
        };
        try {
            Class<?> dc = XposedHelpers.findClass("android.provider.DeviceConfig", lpp.classLoader);
            log("DeviceConfig.getBoolean hooks: "
                    + XposedBridge.hookAllMethods(dc, "getBoolean", flagHook).size());
        } catch (Throwable t) {
            logErr("DeviceConfig", t);
        }
        try {
            Class<?> proxy = XposedHelpers.findClassIfExists(
                    "com.android.systemui.util.DeviceConfigProxy", lpp.classLoader);
            if (proxy != null) {
                log("DeviceConfigProxy.getBoolean hooks: "
                        + XposedBridge.hookAllMethods(proxy, "getBoolean", flagHook).size());
            }
        } catch (Throwable t) {
            logErr("DeviceConfigProxy", t);
        }
    }

    // ---------------------------------------------------------------- pinch zoom + min/max
    private void hookResizeHandler(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> c = findFirst(lpp, RESIZE_HANDLER);
            if (c == null) {
                log("PipResizeGestureHandler NOT FOUND");
                return;
            }
            dump(c);

            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    log("PipResizeGestureHandler constructed");
                    forceEnable(p.thisObject);
                    flush();
                }
            });
            XposedBridge.hookAllMethods(c, "onActivityPinned", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    log("onActivityPinned");
                    forceEnable(p.thisObject);
                    forceMinMaxFields(p.thisObject);
                    flush();
                }
            });
            XposedBridge.hookAllMethods(c, "onInputEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    forceMinMaxFields(p.thisObject);
                }
            });

            XC_MethodHook minHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    overrideSize(p, "min_percent", 30);
                }
            };
            XC_MethodHook maxHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    overrideSize(p, "max_percent", 90);
                }
            };
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName().toLowerCase();
                if (name.contains("minsize") && name.startsWith("update")) {
                    XposedBridge.hookMethod(m, minHook);
                    n++;
                } else if (name.contains("maxsize") && name.startsWith("update")) {
                    XposedBridge.hookMethod(m, maxHook);
                    n++;
                }
            }
            log("min/max update-method hooks: " + n);
        } catch (Throwable t) {
            logErr("resize handler", t);
        }
    }

    /** Turns on every boolean field that looks like an "enabled" flag, then re-inits input. */
    private void forceEnable(Object handler) {
        try {
            if (!prefs().getBoolean("pinch_enabled", true)) return;
            boolean changed = false;
            Class<?> c = handler.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == boolean.class
                            && f.getName().toLowerCase().contains("enable")) {
                        f.setAccessible(true);
                        if (!f.getBoolean(handler)) {
                            f.setBoolean(handler, true);
                            changed = true;
                            log("forced " + f.getName() + " = true");
                        }
                    }
                }
                c = c.getSuperclass();
            }
            if (changed) {
                try {
                    XposedHelpers.callMethod(handler, "updateIsEnabled");
                    log("called updateIsEnabled()");
                } catch (Throwable t) {
                    log("updateIsEnabled() unavailable: " + t);
                }
            }
        } catch (Throwable t) {
            logErr("forceEnable", t);
        }
    }

    /** Fallback: write min/max Point fields directly, keeping their aspect ratio. */
    private void forceMinMaxFields(Object handler) {
        try {
            applyPointField(handler, "mMinSize", prefs().getInt("min_percent", 30));
            applyPointField(handler, "mMaxSize", prefs().getInt("max_percent", 90));
        } catch (Throwable t) {
            logErr("forceMinMaxFields", t);
        }
    }

    private void applyPointField(Object o, String name, int pct) throws Exception {
        Field f = findField(o.getClass(), name);
        if (f == null || f.getType() != Point.class) return;
        f.setAccessible(true);
        Point pt = (Point) f.get(o);
        if (pt == null || pt.x <= 0 || pt.y <= 0) return;
        float ratio = pt.y / (float) pt.x;
        int nx = Math.round(screenWidthPx() * pct / 100f);
        pt.x = nx;
        pt.y = Math.round(nx * ratio);
    }

    private void overrideSize(XC_MethodHook.MethodHookParam p, String key, int def) {
        try {
            if (p.args.length < 2 || !(p.args[0] instanceof Integer)) return;
            int origX = (Integer) p.args[0];
            int origY = (Integer) p.args[1];
            if (origX <= 0) return;
            int pct = prefs().getInt(key, def);
            int newX = Math.round(screenWidthPx() * pct / 100f);
            int newY = Math.round(newX * (origY / (float) origX));
            p.args[0] = newX;
            p.args[1] = newY;
        } catch (Throwable t) {
            logErr("overrideSize", t);
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

    private static synchronized void log(String s) {
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
