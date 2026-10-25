package com.xzl.console

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ProotManager —— 免 Root 的 Proot 用户态模拟运行环境管理。
 *
 * 所有操作都无需 su：直接用当前 App 用户权限执行 proot，系统资源通过 `-b` 参数绑定。
 */
object ProotManager {

    const val ASSET_PREBUILT_DIR = "prebuilts"

    // proot 可执行文件（放在 assets/prebuilts/proot，按设备架构替换）
    private const val PROOT_BIN_FILENAME = "proot"
    private const val PROOT_ASSET_BIN = "$ASSET_PREBUILT_DIR/$PROOT_BIN_FILENAME"

    // rootfs 解压目录（filesDir/proot_env，检查 /bin/sh 判定是否已就绪）
    private const val PROOT_ENV_DIR = "proot_env"

    // rootfs 下载地址（约 30MB 的 .tar.gz）。请按设备架构修改 aarch64 / x86_64。
    // 示例：Alpine minirootfs
    private const val ROOTFS_URL =
        "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/alpine-minirootfs-latest-aarch64.tar.gz"
    private const val ROOTFS_TAR_NAME = "rootfs.tar.gz"

    // 日志面板最多保留的字符数，避免无限增长
    private const val MAX_LOG_CHARS = 200_000

    // App 全局上下文（不持有 Activity）
    @Volatile
    private var appContext: Context? = null

    // proot 进程实例（停止时 destroyForcibly 用）
    private var prootProcess: Process? = null

    // 实时日志流（供 Compose 终端面板展示）
    private val _prootLogs = MutableStateFlow("")
    val prootLogs: StateFlow<String> = _prootLogs.asStateFlow()

    // 下载进度 0-100（供 UI 展示）
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    // 日志读取协程的作用域：独立于任一 ViewModel，避免进程常驻导致任务被取消
    private val prootScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 在 Application / MainActivity 生命周期最早处调用一次即可（设置全局上下文） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun appendLog(line: String) {
        val next = _prootLogs.value + line
        _prootLogs.value = next.takeLast(MAX_LOG_CHARS)
    }

    //=========================================================================
    // 需求 1：提取 proot 二进制
    // 从 assets 拷贝到 filesDir，并直接 setExecutable(true)（免 Root，无需 su）
    //=========================================================================
    private fun ensureProotBinary(ctx: Context): File {
        val dst = File(ctx.filesDir, PROOT_BIN_FILENAME)
        if (dst.exists() && dst.length() > 0) return dst
        ctx.assets.open(PROOT_ASSET_BIN).use { input ->
            FileOutputStream(dst).use { out -> input.copyTo(out) }
        }
        dst.setExecutable(true, false)   // chmod 755（免 Root，不用 su）
        return dst
    }

    /** 判断 rootfs 是否已解压就绪（检查 /bin/sh 是否存在） */
    private fun isRootfsReady(ctx: Context): Boolean {
        val sh = File(ctx.filesDir, "$PROOT_ENV_DIR/bin/sh")
        return sh.exists() && sh.isFile
    }

    //=========================================================================
    // 需求 2：下载并解压 rootfs
    // 返回 true=已就绪；false=失败（错误信息已写入日志）
    //=========================================================================
    suspend fun prepareRootfs(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val ctx = context.applicationContext
            if (isRootfsReady(ctx)) {
                appendLog("✅ rootfs 已就绪（${PROOT_ENV_DIR}/bin/sh）\n")
                return@withContext true
            }

            appendLog("📥 首次使用需下载约 30MB rootfs 系统包...\n")
            val tarGz = try {
                downloadRootfs(ctx)
            } catch (e: Exception) {
                appendLog("❌ 下载失败: ${e.message}\n")
                _downloadProgress.value = 0
                return@withContext false
            }

            appendLog("📦 下载完成，正在解压到 ${PROOT_ENV_DIR}/ ...\n")
            val ok = try {
                extractRootfs(ctx, tarGz)
                tarGz.delete()
                true
            } catch (e: Exception) {
                appendLog("❌ 解压失败: ${e.message}\n")
                false
            }

            if (ok && isRootfsReady(ctx)) {
                appendLog("✅ rootfs 安装完成\n")
            } else if (ok) {
                appendLog("❌ 解压后未发现 ${PROOT_ENV_DIR}/bin/sh\n")
            }
            ok
        }

    private suspend fun downloadRootfs(ctx: Context): File =
        withContext(Dispatchers.IO) {
            _downloadProgress.value = 0
            val dst = File(ctx.cacheDir, ROOTFS_TAR_NAME)
            val conn = URL(ROOTFS_URL).openConnection() as HttpURLConnection
            try {
                conn.connect()
                val total = conn.contentLength
                val input = conn.inputStream
                val output = FileOutputStream(dst)
                val buf = ByteArray(8192)
                var read = input.read(buf)
                var done = 0
                while (read != -1) {
                    output.write(buf, 0, read)
                    done += read
                    if (total > 0) {
                        _downloadProgress.value = (done * 100 / total).coerceIn(0, 100)
                    }
                    read = input.read(buf)
                }
                output.flush()
                output.close()
                input.close()
                _downloadProgress.value = 100
            } finally {
                conn.disconnect()
            }
            dst
        }

    /** 用系统 tar 命令解压 .tar.gz 到 filesDir/proot_env（Dispatchers.IO 内调用） */
    private fun extractRootfs(ctx: Context, tarGz: File) {
        val envDir = File(ctx.filesDir, PROOT_ENV_DIR)
        if (!envDir.exists()) envDir.mkdirs()
        val p = ProcessBuilder(
            "tar", "-xzf", tarGz.absolutePath, "-C", envDir.absolutePath
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        if (p.exitValue() != 0) {
            throw RuntimeException("tar 解压失败: $out")
        }
    }

    //=========================================================================
    // 需求 3：启动 Proot（免 Root）
    // ./proot -r <rootfs> -0 -w / -b /dev -b /proc -b /sys /bin/sh
    //=========================================================================
    suspend fun startProot(context: Context): String =
        withContext(Dispatchers.IO) {
            try {
                val ctx = context.applicationContext
                if (!isRootfsReady(ctx)) {
                    return@withContext "❌ rootfs 未就绪，请先点击启动完成下载"
                }
                val bin = ensureProotBinary(ctx)
                val rootfs = File(ctx.filesDir, PROOT_ENV_DIR)

                val cmd = listOf(
                    bin.absolutePath,
                    "-r", rootfs.absolutePath,
                    "-0",
                    "-w", "/",
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "/sys",
                    "/bin/sh"
                )
                val pb = ProcessBuilder(cmd)
                pb.redirectErrorStream(true)   // 关键：合并 stdout+stderr
                val process = pb.start()

                prootProcess = process
                _prootLogs.value = "▶ Proot 已启动\n"

                // 后台协程循环读日志 -> 实时回显到 StateFlow
                prootScope.launch {
                    process.inputStream.bufferedReader().use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            appendLog("$line\n")
                            line = reader.readLine()
                        }
                    }
                    prootProcess = null
                    appendLog("\n■ Proot 已退出\n")
                }

                "✅ Proot 启动成功"
            } catch (e: Exception) {
                "❌ Proot 启动失败\n${e.message}"
            }
        }

    //=========================================================================
    // 需求 4：停止 Proot（免 Root）
    // 不读取 process.pid，直接 `pkill -9 -f proot`，再 destroyForcibly()
    //=========================================================================
    suspend fun stopProot(): String =
        withContext(Dispatchers.IO) {
            try {
                // 不尝试获取 pid，直接用 pkill 按名称清除（含可能的残留进程）
                ProcessBuilder("pkill", "-9", "-f", "proot")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()

                prootProcess?.destroyForcibly()
                prootProcess = null

                delay(800)
                appendLog("\n■ Proot 已停止\n")
                "✅ Proot 已停止"
            } catch (e: Exception) {
                "❌ Proot 停止失败\n${e.message}"
            }
        }
}
