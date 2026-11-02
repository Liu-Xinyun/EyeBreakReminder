# EyeBreak20 v1.1 · Android 护眼提醒

一个离线、本地运行的 Android 原生 App。**Android 8.0+（minSdk 26）**，使用 Java，targetSdk 35，无网络权限、无账户、无统计上传。

## 新增功能

| 行为 | 普通模式（原功能） | 严格模式（新增） |
| --- | --- | --- |
| 连续亮屏达到间隔（默认20分钟） | 悬浮窗提醒，选「知道了」或「5分钟后提醒」 | 使用设备管理员 `lockNow()` 立即锁屏，并开始20秒休息窗口 |
| 20秒休息窗口内再次亮屏 | 不限制 | 尝试再次锁屏，直到固定的20秒窗口到期；属于**尽力而为**，不保证能阻止提前解锁 |
| 短暂熄屏或锁屏 **≤20秒** | **保留**累计使用时间，熄屏时不计时 | 同左（严格模式的20秒休息窗口除外） |
| 熄屏或锁屏 **>20秒** | 清零，下次亮屏重新计时 | 同左 |
| 20秒休息窗口到期 | 不适用 | 停止再次锁屏；**不会自动解锁**，需用户手动解锁 |

提醒间隔可选 **1–180分钟**；默认20分钟。切换模式或保存提醒间隔会重新开始累计使用时间。

### 计时示例

- 亮屏使用12分钟 → 锁屏10秒 → 再亮屏8分钟：弹窗 / 自动锁屏（只累计20分钟实际亮屏时间）。
- 亮屏使用12分钟 → 锁屏21秒 → 再亮屏：从0开始累计。
- 严格模式20分钟到时：立刻请求锁屏20秒；在20秒内按亮屏键会尝试再锁。20秒结束后不再主动锁屏，但不会自行解锁设备。

## 重要限制：什么是「严格模式」？

此模式不是系统级不可绕过的强制使用限制。Android 提供 `DevicePolicyManager.lockNow()` 让**获得用户明确授权的设备管理员**立即锁屏，但普通第三方 App 无权阻止用户解锁，也不能在20秒到期时自动解锁手机。

- 20秒内 `ACTION_SCREEN_ON` 到来时，会尝试再次锁屏。因此在某些机型上能够达到接近强制休息的效果，但系统、厂商省电策略及广播延迟都可能影响时效。
- `lockNow()` **可能要求你下一次使用PIN/图案/密码解锁**，不一定允许指纹/面部识别直接解锁。请确保知道设备密码再启用。
- 休息时间不可提前手动结束应用的锁定计时；可在休息结束后打开 App 切换回普通模式或停止监测，并在系统设置中撤销设备管理员授权。
- 为尽量减少权限，本项目仅在 `res/xml/device_admin.xml` 申请 `<force-lock />`，**不会要求修改密码、擦除设备或读取私人数据**。

参阅 Android 官方文档：[DevicePolicyManager.lockNow](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#lockNow())、[设备管理员 API](https://developer.android.com/work/device-admin)。

## 安装方法一：Android Studio

1. 安装 [Android Studio](https://developer.android.com/studio)，打开本压缩包中 `EyeBreak20` 目录。
2. 使用 JDK 17、Android SDK Platform 35。工程采用 Gradle 8.11.1 与 Android Gradle Plugin 8.9.2。
3. 如果 Android Studio 提示缺少 Gradle Wrapper：在有 Gradle 8.11.1 的环境运行 `gradle wrapper --gradle-version 8.11.1`，或由 Android Studio 使用已有 Gradle 完成同步。
4. 选择 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。
5. 将 `app/build/outputs/apk/debug/app-debug.apk` 传到手机安装。

## 安装方法二：GitHub Actions（不需要本地安装 Android Studio）

1. 新建 GitHub 仓库，把 **`EyeBreak20` 目录内的文件** 上传到仓库根目录（注意包含隐藏目录 `.github/`）。
2. 在仓库 Actions 页找到 **Build Android APK**，点击 **Run workflow**；push 到 `main` 也会启动构建。
3. 构建完成后下载 `EyeBreak20-debug-apk` artifact，解压并安装其中的 `app-debug.apk`。

## 使用和授权

1. 先打开 App，选择 **普通模式** 或 **严格模式**。
2. 普通模式：点击「授权显示在其他应用上层」，允许悬浮窗。
3. 严格模式：点击「授权设备管理员锁屏」，Android 会出现系统级授权确认界面。需要确认后才可启动严格模式；**不需要额外开启悬浮窗**，但授权悬浮窗有助于锁屏权限被撤销时回退到弹窗。
4. 推荐允许通知，并允许应用后台运行（部分国产 Android 系统还需设置自启动与电池不限制）。
5. 点击「开始监测」。普通模式可点「立即测试弹窗」；严格模式可点「测试严格锁屏（20秒）」确认真实运行效果。**测试严格锁屏前请确认记得设备密码。**
6. 可以勾选「开机后自动恢复监测」。实际行为受 Android 和厂商限制。
7. 点击「停止监测」会停止服务；关闭设备管理员权限可去系统设置 → 设备管理员应用（名称因品牌而异）。卸载前如系统提示管理员仍启用，请先撤销。

## 后台与计时限制

- 通过动态注册 `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` 监听屏幕状态；它不是读取 App 前台使用详情的服务，亮着的锁屏界面也可能被计入亮屏时间。
- `SystemClock.elapsedRealtime()` 使用单调时钟，短暂锁屏时冻结累计亮屏时间；重新亮屏时如果间隔不超过20秒，则推迟下次到期时间同样的秒数。
- 为维持动态广播，使用 `specialUse` 前台服务和常驻通知。系统和厂商可以终止后台服务；Doze/系统延迟可能使提醒或20秒到期状态更新延后。
- 关闭服务后，下次启动重新计时。重启手机时不会沿用上一次启动前的计时。
- Android 13+ 通知权限被禁用时，备用通知可能无法出现。普通模式无悬浮窗权限则会回退到通知。
- 仅用于个人侧载；正式在 Google Play 分发前需检查前台服务 `specialUse` 的审核与声明要求。

## 源代码结构

- `app/src/main/java/com/example/eyebreak20/MainActivity.java`：模式切换、授权、测试、设置、倒计时
- `app/src/main/java/com/example/eyebreak20/ScreenMonitorService.java`：前台服务、屏幕广播、20秒熄屏宽限、普通悬浮窗、严格锁屏与重试
- `app/src/main/java/com/example/eyebreak20/EyeBreakDeviceAdminReceiver.java`：设备管理员组件
- `app/src/main/java/com/example/eyebreak20/TimingRules.java`：纯 Java 的时间边界处理
- `app/src/main/res/xml/device_admin.xml`：仅 `<force-lock />` 管理员政策
- `app/src/main/java/com/example/eyebreak20/BootReceiver.java`：开机恢复（用户选择）
- `tests/TimingRulesSmokeTest.java`：时间边界的 Java 冒烟测试
- `.github/workflows/build-apk.yml`：GitHub Actions 云构建 APK

### 计时规则测试（无需 Android SDK）

在项目根目录执行：

```bash
mkdir -p tests/out
javac -d tests/out app/src/main/java/com/example/eyebreak20/TimingRules.java tests/TimingRulesSmokeTest.java
java -cp tests/out TimingRulesSmokeTest
```

已验证8项关键时间边界测试通过，并验证 Android XML 文件均能解析。**当前执行环境没有 Android SDK/Gradle，因此尚未完成 Android APK 编译与真机测试。请将该版本视为待设备验证的源码工程，而不是已经验证成功的 APK。**
