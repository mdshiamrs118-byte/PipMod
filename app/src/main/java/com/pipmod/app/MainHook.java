package com.pipmod.app;

import android.content.res.Resources;

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
 * Everything is wrapped in try/catch and heavily logged (search "PipMod" in the LSPosed log).
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
    private static final String[] RESIZE_HANDLER = {
            "com.android.systemui.pip.phone.PipResizeGestureHandler",
            "com.android.wm.shell.pip.phone.PipResizeGestureHandler"};

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
        log("Loaded into SystemUI (module is active)");

        try {
            XSharedPreferences p = prefs();
            log("Prefs file: " + p.getFile() + " readable=" + p.getFile().canRead()
                    + " corner_dp=" + p.getInt("corner_dp", -1)
                    + " default_percent=" + p.getInt("default_percent", -1));
        } catch (Throwable t) {
            logErr("prefs check", t);
        }

        hookCornerRadius(lpp);
        hookPinchFlag(lpp);
        hookResizeHandler(lpp);
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
                    // Apply the radius ourselves, so it works even if the field type/name differs.
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
    /** Pinch resize is gated by DeviceConfig flag "pip_pinch_resize"; force it on. */
    private void hookPinchFlag(XC_LoadPackage.LoadPackageParam lpp) {
        XC_MethodHook flagHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    for (Object a : p.args) {
                        if ("pip_pinch_resize".equals(a) && prefs().getBoolean("pinch_enabled", true)) {
                            p.setResult(true);
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
                    forceEnable(p.thisObject);
                }
            });
            XposedBridge.hookAllMethods(c, "onInputEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    forceEnable(p.thisObject);
                }
            });
            XposedBridge.hookAllMethods(c, "onActivityPinned", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    forceEnable(p.thisObject);
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
            log("min/max size hooks: " + n);
        } catch (Throwable t) {
            logErr("resize handler", t);
        }
    }

    private void forceEnable(Object handler) {
        if (!prefs().getBoolean("pinch_enabled", true)) return;
        for (String n : new String[]{"mIsEnabled", "mEnablePinchResize", "mIsPinchResizeEnabled"}) {
            try {
                Field f = findField(handler.getClass(), n);
                if (f != null && f.getType() == boolean.class) {
                    f.setAccessible(true);
                    f.setBoolean(handler, true);
                }
            } catch (Throwable ignored) {
            }
        }
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
    private void hookDefaultSize(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> c = findFirst(lpp, BOUNDS_HANDLER);
            if (c == null) {
                log("PipBoundsHandler NOT FOUND");
                return;
            }
            dump(c);

            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    setDefaultSize(p.thisObject);
                }
            };
            XposedBridge.hookAllConstructors(c, h);
            XposedBridge.hookAllMethods(c, "reloadResources", h);
            XposedBridge.hookAllMethods(c, "onConfigurationChanged", h);
            // Also re-apply right before bounds are computed.
            XposedBridge.hookAllMethods(c, "getDefaultBounds", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    setDefaultSize(p.thisObject);
                }
            });
        } catch (Throwable t) {
            logErr("default size", t);
        }
    }

    private void setDefaultSize(Object handler) {
        try {
            float pct = prefs().getInt("default_percent", 50) / 100f;
            if (!setNumber(handler, "mDefaultSizePercent", pct)) {
                log("mDefaultSizePercent not found");
            }
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

    /** Logs fields and methods so we can fix names for your exact build. */
    private static void dump(Class<?> c) {
        try {
            StringBuilder sb = new StringBuilder("Dump " + c.getName() + "\n  fields:");
            for (Field f : c.getDeclaredFields()) {
                sb.append(' ').append(f.getType().getSimpleName()).append(' ').append(f.getName()).append(';');
            }
            sb.append("\n  methods:");
            for (Method m : c.getDeclaredMethods()) {
                sb.append(' ').append(m.getName()).append('/').append(m.getParameterTypes().length).append(';');
            }
            log(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void log(String s) {
        XposedBridge.log(TAG + s);
    }

    private static void logErr(String what, Throwable t) {
        XposedBridge.log(TAG + "Failed: " + what + " -> " + t);
    }
}
