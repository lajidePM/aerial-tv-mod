# Aerial TV (TCL & 雷鸟电视无缝接管方案)

> **针对 TCL 与雷鸟智能电视（Android 8–14 / CyberUI / Workmode）的 Apple TV 4K 极简桌面方案。**  
> 彻底解决：① 开机自动进入原厂桌面露底；② 在第三方 App 内按遥控器 Home 键回原厂桌面且卡顿 4~5 秒的底层固件死锁。

---

## 核心痛点与技术破案

| 痛点现象 | 底层根因剖析 | 治理方案 |
| :--- | :--- | :--- |
| **开机总是先显示原厂 CyberUI 桌面** | TCL 开机由 `com.tcl.ttvs` 系统服务拉起桌面。默认情况下，系统在冷启动时只认自带的 `com.tcl.cyberui` 或 `com.tcl.workmode`。 | 写入系统级开机直达参数 `boot_app_mode=boot_launcher` 与 `boot_app_componentname`，使原厂开机服务首帧直接启动 Aerial。 |
| **应用内按 Home 键延迟 5 秒回原厂** | TCL 驱动层 `sim-mouse`（仿真鼠标驱动，sources 包含 `0x401`）被 Aerial 官方 `GamepadDevices.isPhysicalGamepad` 误判为游戏手柄（`gamepad=true`）。导致在第三方应用前台时，守护为了防手柄游戏卡死而**主动卸载按键过滤（`filter=false`）**。按键直达系统启动 CyberUI，并触发 Android 系统的 5 秒禁止抢占保护期（`appSwitchDisallowed`）。 | 源码修正：在 `GamepadDevices.kt` 中排除 `sim-mouse` 等 TCL 内建仿真输入设备，使守护在第三方应用中常驻保持 `filter=true`。实体物理遥控器按键在系统处理前被无障碍守护截获，0 延迟直回 Aerial。 |
| **Key Mapper 映射 Home 键失效** | Android 系统底层 `PhoneWindowManager` 将物理 Home 键（`KEYCODE_HOME`）作为系统级特权独占消费，根本不向常规无障碍服务派发。 | **放弃通过 Key Mapper 映射物理 Home 键的死路**，必须通过修复 Aerial 守护自身的 `AccessibilityInputFilter` 按键过滤。 |

---

## 给 AI Agent 的直达执行说明（Agent-Ready Prompt）

如果你正在使用 AI Agent（如 Claude Code, Codex, WorkBuddy, Trae 等）操作电脑与电视，可直接将以下方框内的 Prompt 发送给 Agent 自动执行：

```text
你是一个精通 Android TV 与 ADB 的资深运维专家。请帮我将定制优化版 Aerial TV 安装到我的 TCL/雷鸟电视上，并完成无缝桌面接管。
电视 IP 地址：<请替换为你的电视IP>

操作规范与要求：
1. 请先完整阅读文档并在本机通过 adb 执行命令，不要只提供步骤。
2. 解锁 TCL 固件安装拦截（解除 InstallConfig 限制）。
3. 推送并安装 Aerial 1.82-guard 版本（TCL 固件要求无 CATEGORY_HOME 声明的 guard 版本）。
4. 授予特权：WRITE_SECURE_SETTINGS, SYSTEM_ALERT_WINDOW, GET_USAGE_STATS 及 TCL 自启 AppOps（APP_AUTO_START, APP_ASSOC_START）。
5. 写入 TCL 开机直达配置：
   settings put system boot_app_mode boot_launcher
   settings put system boot_app_componentname app.orchard.tv/.MainActivity
6. 激活无障碍桌面守护服务（保持原有无障碍服务不被冲掉），并执行 AOT Speed 编译优化。
7. 验收要求：运行以下命令并原样贴出输出：
   - adb shell "dumpsys package app.orchard.tv | grep versionName"
   - adb shell "dumpsys activity app.orchard.tv/.MainActivity | grep 'Home Guard'"（确认 filter=true 且 gamepad=false）
   - adb shell "settings get system boot_app_componentname"
```

---

## 极客手动操作指令（分步执行流）

电脑与电视处于同一局域网，替换 `<TV_IP>` 为电视的局域网 IP（例如 `192.168.1.100`）：

### 1. 连接电视并解除 TCL 安装拦截
```bash
adb connect <TV_IP>:5555

# 解除 TCL 固件的安全策略安装拦截
adb -s <TV_IP>:5555 shell settings put global verifier_verify_adb_installs 0
adb -s <TV_IP>:5555 shell settings put global package_verifier_enable 0
adb -s <TV_IP>:5555 shell "content update --uri content://com.tcl.providers.config/InstallConfig --bind config_content:s:false"
adb -s <TV_IP>:5555 shell am force-stop com.android.packageinstaller
```

### 2. 推送并安装定制版 APK
由于 TCL 深度修改了底层 `/system/bin/pm`，直接 `adb install` 会报 `install_switch_flag: 0`。推荐以下最稳安装通道：
```bash
# 推送至电视内部存储
adb -s <TV_IP>:5555 push aerial-tcl-1.82-guard.apk /sdcard/Download/Aerial.apk

# 调起电视上当贝市场本地安装器（屏幕上按确认安装）：
adb -s <TV_IP>:5555 shell am start -n com.dangbeimarket/.ui.apkmanager.ApkManagerActivity

# （若无当贝市场，可通过电视自带多屏互动推送，或使用系统自带文件管理器打开安装）
```

### 3. 系统特权授予与自启放行
```bash
adb -s <TV_IP>:5555 shell pm grant app.orchard.tv android.permission.ACCESS_FINE_LOCATION
adb -s <TV_IP>:5555 shell pm grant app.orchard.tv android.permission.WRITE_SECURE_SETTINGS
adb -s <TV_IP>:5555 shell appops set app.orchard.tv SYSTEM_ALERT_WINDOW allow
adb -s <TV_IP>:5555 shell appops set app.orchard.tv WRITE_SETTINGS allow
adb -s <TV_IP>:5555 shell appops set app.orchard.tv REQUEST_INSTALL_PACKAGES allow
adb -s <TV_IP>:5555 shell appops set app.orchard.tv GET_USAGE_STATS allow
adb -s <TV_IP>:5555 shell appops set app.orchard.tv android:get_usage_stats allow
adb -s <TV_IP>:5555 shell cmd notification allow_listener app.orchard.tv/app.orchard.tv.guard.RevivalListener

# TCL 专属自启 AppOps 放行
adb -s <TV_IP>:5555 shell appops set app.orchard.tv APP_AUTO_START allow
adb -s <TV_IP>:5555 shell appops set app.orchard.tv APP_ASSOC_START allow
```

### 4. 写入开机直达参数（核心）
```bash
adb -s <TV_IP>:5555 shell settings put system boot_app_mode boot_launcher
adb -s <TV_IP>:5555 shell settings put system boot_app_componentname app.orchard.tv/.MainActivity
```

### 5. 激活守护、接管屏保与 AOT 编译优化
```bash
# 启动 Aerial
adb -s <TV_IP>:5555 shell am start -W -n app.orchard.tv/.MainActivity

# 将 AerialDream 设为系统屏保并保持 ADB 常开
adb -s <TV_IP>:5555 shell settings put secure screensaver_components app.orchard.tv/app.orchard.tv.dream.AerialDream
adb -s <TV_IP>:5555 shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez own_screensaver true
adb -s <TV_IP>:5555 shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez keep_adb true

# 激活无障碍守护（保留系统原有 walleve 等无障碍服务，末尾追加 Aerial 守护）
EXISTING_SERVICES=$(adb -s <TV_IP>:5555 shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$EXISTING_SERVICES" in
  *"app.orchard.tv/.guard.HomeGuardService"*) ;;
  ""|null) adb -s <TV_IP>:5555 shell settings put secure enabled_accessibility_services "app.orchard.tv/app.orchard.tv.guard.HomeGuardService" ;;
  *) adb -s <TV_IP>:5555 shell settings put secure enabled_accessibility_services "${EXISTING_SERVICES}:app.orchard.tv/app.orchard.tv.guard.HomeGuardService" ;;
esac
adb -s <TV_IP>:5555 shell settings put secure accessibility_enabled 1

# 满血 Speed 模式全量 AOT 编译（大幅提升 Compose 渲染与按键响应帧率）
adb -s <TV_IP>:5555 shell cmd package compile -m speed -f app.orchard.tv

# 清理临时推送的安装包
adb -s <TV_IP>:5555 shell rm -f /sdcard/Download/Aerial.apk
```

---

## 源码补丁说明（开发者参考）

针对 upstream `pengchujin/aerial-tv`，在 `app/src/main/java/app/orchard/tv/guard/GamepadDevices.kt` 中追加排除 TCL 电视内部仿真输入驱动：

```kotlin
// GamepadDevices.kt -> isPhysicalGamepad()
if (name == HILISILICON_PROJECTOR_KEYBOARD_NAME &&
    vendorId == HILISILICON_PROJECTOR_KEYBOARD_VENDOR_ID &&
    productId == HILISILICON_PROJECTOR_KEYBOARD_PRODUCT_ID
) return false

// 追加：排除 TCL / MTK 仿真设备与遥控器接收端（防止将其 Sources 中的 0x401 误判为游戏手柄）
when (name) {
    "sim-mouse", "sim-TP", "sim-kb", "gIrTouch_Mouse", "MTK TV KEYPAD" -> return false
}
if (name?.startsWith("Tcl Smart TV", ignoreCase = true) == true ||
    name?.startsWith("TCL_RC", ignoreCase = true) == true
) return false
```

---

## 验收与自检（Verification）

在电视上打开任意第三方应用（如 B站、云视听极光、当贝市场等），在电脑上运行：
```bash
adb -s <TV_IP>:5555 shell "dumpsys activity app.orchard.tv/.MainActivity | grep 'Home Guard'"
```
**期望结果**：
`filter=true` 且 `gamepad=false`（说明守护处于生效状态，未被误判手柄停用）。此时用实体遥控器按主页键，即可体验 0 延迟秒回 Aerial 桌面。
