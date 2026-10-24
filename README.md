# XZL 控制中枢

极简单页 Linux 容器控制台 App，Kotlin + Jetpack Compose 编写，深色极客风。

## 功能

- 顶部栏：`XZL 控制中枢` + 设置入口（已适配状态栏避让）
- 配置面板：切换目标 Linux 系统（Ubuntu / Debian / Alpine）、纯终端 / XZFace 桌面模式、SSH / VNC 开关、运行后端选择
- 运行后端：`Chroot`（需 Root）/ `QEMU`（免 Root）
- 状态区：显示 `已停止` / `运行中`（绿）及内存占用
- 核心按钮：`▶️ 启动容器` / `⏹ 停止容器`
- 快捷工具栏：`打开终端` / `连接 XZFace`（仅运行中可用）
- 增强工具栏：`安装桌面` / `安装 AVNC` / `设为默认`（Root 静默安装与默认桌面设置）
- QEMU 终端面板：实时日志滚动回显，支持镜像下拉选择与一键启动 / 停止

## 运行后端

### Chroot（Root）

主按钮通过 `su` 执行 chroot 脚本：

```
sh /data/local/chroot/start.sh start   # 启动
sh /data/local/chroot/start.sh stop    # 停止
```

脚本成功时打印 `Start Success` / `Stop Success`，App 据此更新 UI；失败则 Toast 报错。脚本路径可在 `MainActivity.kt` 中 `CHROOT_SCRIPT` 常量修改。

### QEMU（免 Root）

QEMU 二进制与磁盘镜像放在 `assets/prebuilts/` 下，首次启动时拷贝到 `filesDir` 并 `chmod 755`，随后用 `ProcessBuilder` 启动并 `redirectErrorStream(true)`，日志通过 `StateFlow` 实时回显到终端面板。

## RootManager

所有 Root / QEMU 底层操作封装在 `RootManager.kt`：

- `installApkFromAssets(context, apkName)`：将 assets 中的 APK 拷贝到 cacheDir 并 `su -c pm install -r` 静默安装，捕获 stdout/stderr 返回具体错误
- `setDefaultLauncher()`：`cmd package set-home-activity` 设置默认桌面，自动解析已安装的 Launcher Activity
- `executeRootScript(script)`：`ProcessBuilder` + `redirectErrorStream(true)`，无有效输出时自动将脚本迁移至 `filesDir` 并 `chmod 755` 容错重试
- `startQemuVM(context, image)` / `stopQemuVM()`：免 Root 虚拟机启动 / 停止与日志流

预置资源位于 `app/src/main/assets/prebuilts/`：

- `Lawnchair-1.2.0.1884.apk`：桌面启动器
- `AVNC-3.3.1.apk`：VNC 客户端
- `qemu-system-aarch64`：QEMU 二进制（需自行放入）
- `debian.img` / `ubuntu.img` / `alpine.img`：虚拟磁盘镜像（需自行放入）

## 构建

需要 Android SDK 35、JDK 17、已 root / Magisk / SuperSU 设备（Chroot 模式）。

```bash
./gradlew assembleDebug
```