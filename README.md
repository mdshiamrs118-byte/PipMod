# PiP Mod (LSPosed, Android 11)

Xposed/LSPosed module for **stock Android 11** that customizes Picture-in-Picture in SystemUI:

- Two-finger pinch zoom
- Default size
- Minimum / maximum size
- Corner radius

## Install
1. Push this repo to GitHub. The **Android CI** action builds the APK (Actions tab -> latest run -> Artifacts).
2. Install the APK, open LSPosed, enable **PiP Mod**, scope: **System UI**.
3. Open the app, set your values, then restart SystemUI (`su -c killall com.android.systemui`) or reboot.

## Notes
- Hooks target AOSP Android 11 class names (`com.android.systemui.pip.*`). If something doesn't apply,
  check the LSPosed log for `[PipMod]` lines and compare names with your SystemUI in jadx.
- Not for Android 12+ (PiP moved to `com.android.wm.shell`).
