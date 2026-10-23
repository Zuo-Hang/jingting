# 静厅

我们自己用的客厅桌面。从 [CleanYourTV](https://github.com/Gathin23/cleanyourtv) 改出来，不再接管系统 Home。

它是一个**普通应用**，电视上显示为 **静厅**，包名仍是 `local.tv.cleanapps`（覆盖安装不会丢掉数据）。原厂桌面还在，不会抢 Home 键，也不会卸载系统应用。

当前版本：`2.6-ordinary.45`（versionCode 54）。最低 Android 8（API 26）。

## 和原版的差别

| | 原版 CleanYourTV | 我们这版 |
|---|---|---|
| 角色 | 替换系统桌面（HOME） | 普通应用，和原厂桌面并存 |
| 安装 | 按机型清预装、设成默认 Home | 只装这个 APK，不接管 Home |
| 界面 | 程序坞 + 应用网格 | 在此基础上加了液态玻璃、壁纸轮播、清屏 |

不要对电视执行 `pm uninstall`，不要刷机、不要 root。需要停用预装时只用 `pm disable-user`，并且先备份。

## 桌面上有什么

- **程序坞**和右上角壁纸、Wi-Fi 按钮：液态玻璃（[Liquid-Glass-Android](https://github.com/QWEA0/Liquid-Glass-Android) v2.0.11）
- **长按菜单**同样是玻璃底板；Wi-Fi 长按是桌面设置、模块卡片、编辑程序坞、调整应用顺序
- **壁纸按钮**：点一下随机换一张；长按打开背景菜单（一键清屏、更换背景、自动切换、播放顺序、自动进入屏保）
- **换图**：新旧两张 1080p 叠大约 1 秒淡入，淡完丢掉上一张。做法兼容 Android 11，不用 12 以后的模糊 API
- **一键清屏 / 自动屏保**：中间翻页钟 + 日期，背景继续用主界面正在看的那张，不会另换一张。按任意键，或点屏幕，回到主界面，背景也不变
- 时钟、日期、问候语是普通文字，没有做成玻璃字

## 在电脑上编译

需要 JDK 17、Android SDK（compileSdk 34）。签名用 `launcher/app/build.gradle` 里那把调试钥匙，这样覆盖安装不会丢掉数据。

```bash
cd launcher
./gradlew --no-daemon assembleDebug
```

APK 在：

`launcher/app/build/outputs/apk/debug/app-debug.apk`

`local.properties` 里的 `sdk.dir` 指向本机 SDK，不要提交。

## 装到电视上

电视和电脑在同一网络。先打开开发者选项里的 USB 调试或网络调试，电视上点一次「允许」。

```bash
adb connect <电视IP>:5555
adb -s <电视IP>:5555 install -r launcher/app/build/outputs/apk/debug/app-debug.apk
```

装完在应用列表里打开 **静厅**。用完后关掉网络调试、撤销授权，不要把 ADB 钥匙和电视 IP 写进仓库。

## 日常用法

| 操作 | 结果 |
|---|---|
| 点壁纸按钮 | 随机换一张，约 1 秒淡入 |
| 长按壁纸按钮 | 背景菜单 |
| 长按 Wi-Fi 按钮 | 桌面相关功能 |
| 一键清屏 / 等到自动屏保 | 只留翻页钟和当前壁纸 |
| 清屏时按键或点击 | 回到主界面，壁纸不换 |

图片来自电视媒体库或本地点选。把照片放到电视存储或 U 盘，等系统扫描到即可。

## 不要提交的内容

`backup/`、`shots/`、`work/`、`tools/adbkey/`、APK、电视序列号、Wi-Fi 名、账号截图。`.gitignore` 已排除这些。

## 来源

界面和工具基于 CleanYourTV（MIT，Copyright 2026 Gathin）。液态玻璃来自 QWEA0/Liquid-Glass-Android。本目录已与上游 git 历史断开，只保留我们自己的提交。
