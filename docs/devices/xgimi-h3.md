# 极米 H3（国行）

- 销售型号 H3。`ro.product.model` = `XGIMI TV`，厂商 `Xgimi Technology Co.,Ltd`，`ro.product.device` / `ro.product.board` = `ironman`，`ro.product.name` = `Product_ys`，`ro.hardware` / `ro.board.platform` = `m7221`。
- 指纹 `Xgimi/ironman/ironman:8.0.0/OPR5.170623.014/v1.15.93:user/dev-keys`。Android 8.0.0（SDK 26），安全补丁 2017-12-01，`OPR5.170623.014`，`dev-keys`。`ro.product.cpu.abi` = `arm64-v8a`。
- 屏幕 1920×1080、240dpi、60Hz。内存 2709 MB，`ro.config.low_ram=true`。网络 Wi-Fi。
- 实测 Aerial 1.57 (69) `standard`，包名 `app.orchard.tv`。测试日期 2026-10-26。未 root、未刷机。

## 开 ADB：系统里没有入口

这台机器的设置里没有可用的开发者选项。ADB 没打开时，`adb connect <IP>:5555` 返回 `Connection refused`。

可行的做法与 [Z7X](xgimi-z7x.md) 用的是同一个工具，但安装方式不同：在手机上下载 [code-ant/z7x-adb](https://github.com/code-ant/z7x-adb)（极米 ADB 开启器）的 APK，用极米手机 App 上传到投影仪并安装，然后打开一次。不需要 U 盘。该工具原先只在 Z7X 上验证过；这次在 H3 上同样能打开网络 ADB，随后可以正常安装应用。普通应用权限即可，不需要 root，也不进工厂模式。

第三方工具自行评估风险。Aerial 本身不依赖它，它只是这台机器打开 ADB 的入口。工具作者也写了不保证全部极米型号，这里只记录 H3 这一台的结果。

## 安装与使用

ADB 打开之后，按仓库的正常流程安装即可。这次实测装上的是 Aerial 1.57 (69) `standard`，可以正常使用。

开机后是否自动回到 Aerial、屏保接管、长按 Home 的行为，这次没有单独测量，不要沿用 Z7X 笔记里的那些结论。`ro.config.low_ram=true`，但是否因此走了「流畅」效果，这次也没有单独确认。
