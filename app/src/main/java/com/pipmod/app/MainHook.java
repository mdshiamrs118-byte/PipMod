package com.pipmod.app;

import android.content.res.Resources;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks SystemUI's PiP classes on stock Android 11 (AOSP / Pixel).
 * Class and field names are from AOSP android-11.0.0_r*. Every hook is wrapped
 * in try/catch so a wrong name only logs an error instead of crashing SystemUI.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[PipMod] ";
    private static final String PKG = "com.pipmod.app";

    private static final String CLS_SURFACE_HELPER = "com.android.systemui.pip.PipSurfaceTransactionHelper";
    private static final String CLS_BOUNDS_HANDLER = "com.android.systemui.pip.PipBoundsHandler";
    private static final String CLS_RESIZE_HANDLER = "com.android.systemui.pip.phone.PipResizeGestureHandler";

    private XSharedPreferences prefs;

    private XSharedPreferences prefs() {
        if (prefs == null) {
            prefs = new XSharedPreferences(PKG, "settings");
            prefs.makeWorldReadable();
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
        log("Loaded into SystemUI");

        hookCornerRadius(lpp);
        hookResizeHandler(lpp);
        hookDefaultSize(lpp);
    }

    // ---------------------------------------------------------------- corner radius
    private void hookCornerRadius(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> c = XposedHelpers.findClass(CLS_SURFACE_HELPER, lpp.classLoader);

            // Set the radius right before every "round(...)" call, so it always
            // uses our value (also after config/density changes).
            XposedBridge.hookAllMethods(c, "round", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    setCorner(p.thisObject);
                }
            });
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    setCorner(p.thisObject);
                }
            });
            log("Corner radius hook installed");
        } catch (Throwable t) {
            logErr("corner radius", t);
        }
    }

    private void setCorner(Object helper) {
        try {
            float dp = prefs().getInt("corner_dp", 16);
            float px = dp * density();
            XposedHelpers.setFloatField(helper, "mCornerRadius", px);
        } catch (Throwable t) {
            logErr("setCorner", t);
        }
    }

    // ---------------------------------------------------------------- pinch zoom + min/max
    private void hookResizeHandler(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> c = XposedHelpers.findClass(CLS_RESIZE_HANDLER, lpp.classLoader);

            // Force pinch-to-resize on.
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    forceEnable(p.thisObject);
                }
            });
            // The flag can be re-read from DeviceConfig on later calls; re-force it.
            XposedBridge.hookAllMethods(c, "onActivityPinned", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    forceEnable(p.thisObject);
                }
            });
            XposedBridge.hookAllMethods(c, "onInputEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    forceEnable(p.thisObject);
                }
            });

            // Override min / max size (args are x, y in pixels; keep aspect ratio).
            XposedBridge.hookAllMethods(c, "updateMinSize", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    overrideSize(p, "min_percent");
                }
            });
            XposedBridge.hookAllMethods(c, "updateMaxSize", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    overrideSize(p, "max_percent");
                }
            });
            log("Resize handler hooks installed");
        } catch (Throwable t) {
            logErr("resize handler", t);
        }
    }

    private void forceEnable(Object handler) {
        if (!prefs().getBoolean("pinch_enabled", true)) return;
        // Field name differs between 11 builds; try the known ones.
        String[] names = {"mIsEnabled", "mEnablePinchResize", "mIsPinchResizeEnabled"};
        for (String n : names) {
            try {
                XposedHelpers.setBooleanField(handler, n, true);
            } catch (Throwable ignored) {
            }
        }
    }

    private void overrideSize(XC_MethodHook.MethodHookParam p, String key) {
        try {
            if (p.args.length < 2 || !(p.args[0] instanceof Integer)) return;
            int origX = (Integer) p.args[0];
            int origY = (Integer) p.args[1];
            if (origX <= 0) return;
            int defPct = key.equals("min_percent") ? 30 : 90;
            int pct = prefs().getInt(key, defPct);
            int newX = Math.round(screenWidthPx() * pct / 100f);
            int newY = Math.round(newX * (origY / (float) origX));
            p.args[0] = newX;
            p.args[1] = newY;
        } catch (Throwable t) {
            logErr("overrideSize", t);
        }
    }

    // ---------------------------------------------------------------- default size
    private void hookDefaultSize(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> c = XposedHelpers.findClass(CLS_BOUNDS_HANDLER, lpp.classLoader);

            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    setDefaultSize(p.thisObject);
                }
            });
            XposedBridge.hookAllMethods(c, "reloadResources", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    setDefaultSize(p.thisObject);
                }
            });
            XposedBridge.hookAllMethods(c, "onConfigurationChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    setDefaultSize(p.thisObject);
                }
            });
            log("Default size hook installed");
        } catch (Throwable t) {
            logErr("default size", t);
        }
    }

    private void setDefaultSize(Object handler) {
        try {
            float pct = prefs().getInt("default_percent", 50) / 100f;
            XposedHelpers.setFloatField(handler, "mDefaultSizePercent", pct);
        } catch (Throwable t) {
            logErr("setDefaultSize", t);
        }
    }

    // ---------------------------------------------------------------- logging
    private static void log(String s) {
        XposedBridge.log(TAG + s);
    }

    private static void logErr(String what, Throwable t) {
        XposedBridge.log(TAG + "Failed: " + what + " -> " + t);
    }
}
