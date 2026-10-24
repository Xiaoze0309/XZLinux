# XZL 控制中枢

极简单页 Linux 容器控制台 App，Kotlin + Jetpack Compose 编写，深色极客风。

## 功能

- 顶部栏：`XZL 控制中枢` + 设置入口
- 配置面板：切换目标 Linux 系统（Ubuntu / Debian / Alpine）、纯终端 / XZFace 桌面模式、SSH / VNC 开关
- 状态区：显示 `已停止` / `运行中`（绿）及内存占用
- 核心按钮：`▶️ 启动容器` / `⏹ 停止容器`
- 快捷工具栏：`打开终端` / `连接 XZFace`（仅运行中可用）

## 容器启停

主按钮通过 `su`（root 权限）执行 chroot 脚本：

```
sh /data/local/chroot/start.sh start   # 启动
sh /data/local/chroot/start.sh stop    # 停止
```

脚本需要在成功时打印 `Start Success` / `Stop Success`，App 据此更新 UI 状态，失败则 Toast 报错。
脚本路径可在 `MainActivity.kt` 中 `CHROOT_SCRIPT` 常量修改。

> 需要已 root / Magisk / SuperSU 的设备。

## 构建

需要 Android SDK 35、JDK 17。

```bash
./gradlew assembleDebug
```
