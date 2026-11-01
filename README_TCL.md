# Aerial TV for TCL 98T7E (tcl_m7642, Android 9 SDK 28)

## 激进重构守护引擎：0ms 压制 + 0 闪烁接管 TCL CyberUI

针对 TCL 98T7E (Android 9 / com.tcl.cyberui) 深度定制的 Aerial TV。保留官方成熟的 Apple TV 4K 航拍流与 Compose TV 界面，彻底重构 `HomeGuardService`，消灭原厂桌面闪退与回弹延迟。

---

### 核心改动点

1. **彻底废除妥协等待逻辑，实现 0ms 压制**
   - 彻底废除 `SETTLE_MS`（1500ms）、`bootStockHolding`（开机刻意保留原厂桌面几秒）、`frontAfterHome`（Home 键后 1500ms usageStats 轮询）等延迟。
   - `onAccessibilityEvent` 一旦捕捉到 `com.tcl.cyberui`，0ms 立即压制启动 Aerial，跳过原有 1000ms 抑制节流。
   - `ACTION_CLOSE_SYSTEM_DIALOGS` (homekey) 广播接收器与 `onKeyEvent` (KEYCODE_HOME) 收到主页键瞬间立即压制。

2. **顶层全屏防露底遮罩（Anti-Flicker Overlay）**
   - 基于 Android 9 (SDK 28) 的 `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`（纯黑 OPAQUE 材质、`FLAG_NOT_FOCUSABLE`、`FLAG_LAYOUT_IN_SCREEN`）。
   - 在按 Home 键或检测到即将切回 CyberUI 的瞬间 **0ms 覆盖全屏**，死死遮盖原厂桌面，彻底消灭视觉闪烁。
   - 在 Aerial（MainActivity）进入前台（`onResume`）时瞬间移除遮罩，实现 Apple TV 般无缝原生切换。
   - 内置 1000ms 超时强制解除机制，确保极端异常情况下绝不黑屏卡死。

3. **0 动画极速转场**
   - `MainActivity.bringBackIntent` 增加 `FLAG_ACTIVITY_NO_ANIMATION` 与 `FLAG_ACTIVITY_REORDER_TO_FRONT`。
   - 启动 Intent 配合 `ActivityOptions.makeCustomAnimation(context, 0, 0)`。
   - `MainActivity` 在 `onCreate`、`onResume`、`onNewIntent` 中显式调用 `overridePendingTransition(0, 0)`。

4. **TCL 自启豁免与 keep_adb 常开守护**
   - 维持 ADB 监听通道及 `keep_adb` 开机保活逻辑。
   - 在服务连接时自动下发 TCL 专属后台自启动 AppOps 豁免（`APP_AUTO_START`, `APP_ASSOC_START`, `AUTO_START`, `ASSOC_START`, `SYSTEM_ALERT_WINDOW`）。

---

### 安装与部署命令 (ADB)

```bash
# 1. 安装 Guard Release APK
adb install -r app-guard-release.apk

# 2. 授予 TCL 必须的系统特权与自启豁免
adb shell pm grant app.orchard.tv android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant app.orchard.tv android.permission.SYSTEM_ALERT_WINDOW
adb shell appops set app.orchard.tv SYSTEM_ALERT_WINDOW allow
adb shell appops set app.orchard.tv GET_USAGE_STATS allow
for op in APP_AUTO_START APP_ASSOC_START AUTO_START ASSOC_START; do
    adb shell appops set app.orchard.tv "$op" allow 2>/dev/null || true
done

# 3. 激活无障碍守护引擎
adb shell settings put secure enabled_accessibility_services app.orchard.tv/.guard.HomeGuardService
adb shell settings put secure accessibility_enabled 1

# 4. 开启 ADB 常开与屏保守护广播
adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez keep_adb true
```
