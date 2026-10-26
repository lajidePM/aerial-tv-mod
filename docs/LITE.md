# Aerial Lite（Android 4.0–4.4）

给 Android 4.x 老电视用的精简版桌面，模块是 `lite/`，包名 `app.orchard.tv.lite`，minSdk 14（Android 4.0），和完整版用同一把签名。

## 为什么是单独的 App

完整版的界面全部用 Jetpack Compose 写，Compose 从来没支持过 Android 5.0 以下，所以没法靠降低 minSdk 来支持 4.x。Lite 用原生 View 重写了界面，不依赖 AndroidX 和 Compose，release 包约 120 KB。

- 包名独立：4.x 电视本来就装不了完整版，两者分开发版、分开升级，versionCode 也互不影响。
- 和完整版共用的部分：`aerials.json` 构建时从 `app/src/main/assets` 复制过来（`lite/lite-assets/`，不进版本库）；Apple 根证书 `raw/apple_root_ca.pem` 复制了一份。
- 移植过来的代码：内置壁纸 `Gradients.kt` 和 `Images.kt` 的一部分（只改了包名和 `createBitmap`），农历 `Lunar.kt`（同一张表，`java.time` 换成 `Calendar`，因为 4.x 上用 `java.time` 要做脱糖，会多出一个 dex；`LunarTest` 用的是和完整版相同的日期）。改了完整版里这几处时，要同步改 Lite。

## 性能原则（比外观优先）

4.x 电视的 CPU 慢，Dalvik 解释执行，GPU 弱，有的盒子根本没有可用的硬件加速。所以：

- **每帧最多一次全屏绘制**：壁纸按屏幕尺寸预先画好、压暗，烘焙成一张图作为窗口背景（`Wallpapers`），存成 `files/backdrop.png`，冷启动只解码一个文件。用 RGB_565（不透明，传纹理和填充的数据量都减半）。
- **不做实时模糊、阴影或玻璃效果**：面板是不透明的纯色，后面不叠压暗层。
- **磁贴是独立的 View，用 translation 定位，焦点放大改 scale**：这些都是 View 属性，开着硬件加速时改动不用重新录制绘制内容，主线程几乎没有负担。圆角直接画进磁贴位图，绘制时不裁剪。
- **没有硬件加速时不做动画**（`HomeView.smooth`）：焦点和滚动直接跳到位，每按一次键只画一帧。在 Android-x86 4.4（软件渲染）上测过：10 次按键从 62 帧降到 10 帧。
- 软件渲染时一帧只重画脏区域，其余从上一块缓冲拷贝。新装后第一次运行时那块缓冲可能还是黑的：Android-x86 4.4 上打开设置面板，除了顶部一条，壁纸全黑（全新安装 2 次都复现）。所以没有硬件加速时，打开、关闭面板会整窗重画（`redrawAllInSoftware`），修改后全新安装 2 次都正常。
- **空闲时没有任何动画**：时钟每分钟整点更新一次，其余时间 0 帧。
- 时钟容器 `hasOverlappingRendering() = false`，淡出时不分配离屏缓冲。
- 磁贴图在两个后台线程里生成，按「包名 + lastUpdateTime + 尺寸」缓存到 `cache/tiles/`，冷启动只读 PNG。
- 不用 Kotlin 协程和 AndroidX，只用 `Executor` 和 `Handler`。

## 功能

| 功能 | Lite | 说明 |
|---|---|---|
| App 网格（5 列）、焦点、滚动 | ✅ | 4.x 的 App 没有 TV 横幅，磁贴都由图标生成：图标主色的斜向渐变 + 图标 |
| 原桌面磁贴 | ✅ | 只声明了 HOME 的固件桌面也列出来 |
| 长按 / 菜单键菜单：移动、隐藏、应用信息、卸载 | ✅ | 长按按「按键重复」判断；有的红外遥控器不发重复，可以用菜单键 |
| 隐藏的 App | ✅ | 设置 → 隐藏的 App |
| 大时钟、日期、农历 | ✅ | |
| 内置壁纸（14 张） | ✅ | 和完整版同一套 |
| 屏保：时钟 / 航拍 | ✅（航拍画面待真机） | 见下 |
| HOME 键回到第一个磁贴 | ✅ | |
| 每日一图、照片壁纸、航拍壁纸 | ❌ | |
| 手机配置页、天气 | ❌ | |
| 控制中心（音量、亮度、信号源） | ❌ | 信号源依赖 Android 5.0 的 `TvInputManager`，4.x 上只能走厂商私有接口 |
| 守护版（无障碍接管 HOME） | ❌ | Lite 只有 standard 版（声明 HOME） |
| 文件夹、家长密码、应用内升级 | ❌ | |

### 航拍屏保

Android 4.x 不信任 Apple 的根证书，系统播放器也没法传入自定义证书，而 `sylvan.apple.com` 的 HTTP 现在会 301 跳到 HTTPS（2026-10 实测），所以完整版在 5.x、6.x 上用的「改走 HTTP」在这里行不通。Lite 在本机起一个只监听 127.0.0.1 的小代理（`FilmProxy`）：播放器打开 `http://127.0.0.1:<端口>/f?u=<地址>`，代理用 HTTPS 去取，只信任打包的 Apple 根证书，并打开 4.1–4.4 默认关掉的 TLS 1.2（4.0 用 TLS 1.0，sylvan 也接受），Range 请求原样转发。只代理 `https://sylvan.apple.com/` 下的地址。

片源用 1080p H.264（`url` 字段）。20 秒没准备好、或者出错就换下一段；连续 3 次失败改成时钟屏保。

## 模拟器

新版 Android 模拟器在 Apple Silicon 上不能跑 ARM32 和 x86 的 4.x 镜像（`CPU Architecture 'arm' is not supported`），SDK Tools 25 的 classic 模拟器在 Rosetta 下会崩溃。所以用 Homebrew 的 QEMU 跑 Android-x86 的 Live ISO（TCG 软件模拟，慢，但能用）：

- **4.4.4**：`android-x86-4.4-r5.iso`（SourceForge）。`qemu-system-x86_64 -cpu qemu64 -smp 2`，内核参数 `androidboot.hardware=android_x86 nomodeset UVESA_MODE=1280x720 console=ttyS0`，网卡 `rtl8139`，`hostfwd` 把 5555 转出来就能 adb。开机后先在串口 shell 里 `echo x > /sys/power/wake_lock`，否则熄屏后系统挂起，adb 跟着断；`settings put global package_verifier_enable 0`，否则 `adb install` 卡在 Google 的「验证应用」上。没有 GLES2，App 走软件渲染。
- **4.0.4**：archive.org 上的 `android-x86-4.0-eth0-asus_laptop-20120516.iso`。要用 `qemu-system-i386 -cpu n270 -smp 1`（init 用了 SSSE3，pentium3 会反复重启；qemu64 卡在内核），`androidboot.hardware=asus_laptop vga=788 console=ttyS0`。这个镜像只带 Atheros 和 r8169 网卡驱动，QEMU 都不模拟，所以没有网络；SurfaceFlinger 依赖 Intel 显卡，屏幕上没有画面。传文件靠串口 shell 的 `base64`，检查界面靠 App 把自己的 View 树画进位图（临时代码，没有提交）。

## 验证情况（2026-10-26，模拟器，没有真机）

- 4.4.4（1280×720）：首页、焦点、滚动、长按菜单（松手不会误触第一项）、移动并保存顺序、设置面板、换壁纸、HOME 键复位、打开 App、时钟屏保。冷启动到第一帧 195–343 ms，磁贴（缓存）约 250 ms 就绪，进程 PSS 约 16 MB。航拍：代理的 HTTPS、证书、Range 都正常（206，读到文件末尾的索引），但虚拟机里的解码器解不了 H.264 High，画面没出来，失败 3 次后改为时钟，符合预期。
- 4.0.4（800×600）：安装、启动、加载 43 个 App、焦点、滚动、菜单键菜单、设置面板，没有 VerifyError 或 NoSuchMethodError。
- 还没在真机上看过：航拍画面、硬件加速下的帧耗时、长按（按键重复）在红外遥控器上的表现、开机进桌面。

## 构建和安装

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :lite:assembleRelease :lite:testReleaseUnitTest :lite:lintRelease
adb install -r lite/build/outputs/apk/release/lite-release.apk
```

`tools/install.sh` 还不支持 Lite。装好后按 HOME 键，在系统弹出的选择框里选 Aerial Lite 并设为默认。
