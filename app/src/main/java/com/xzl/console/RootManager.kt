package com.xzl.console

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// 命令执行结果封装
internal data class CommandResult(val output: String, val exitCode: Int)

/**
 * RootManager —— 集中封装 Root 调用。
 *
 * Root 执行统一走 `su` 进程并把命令写入 stdin（兼容 Magisk / SuperSU 交互式授权），
 * 同时 redirectErrorStream(true) 合并 stdout/stderr，解决"无有效输出"问题。
 */
object RootManager {

    // ---- assets/prebuilts 下预置文件 ----
    const val ASSET_PREBUILT_DIR = "prebuilts"
    const val LAWNCHAIR_APK = "Lawnchair-1.2.0.1884.apk"
    const val AVNC_APK = "AVNC-3.3.1.apk"

    // ---- Lawnchair 默认桌面 ----
    private val LAWNCHAIR_PACKAGES = listOf("app.lawnchair", "ch.deletescape.lawnchair")

    // App 全局上下文（仅用于 filesDir 容错拷贝等需要路径的场景，不持有 Activity）
    @Volatile
    private var appContext: Context? = null

    /** 在 Application / MainActivity 生命周期最早处调用一次即可（设置全局上下文） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    //=========================================================================
    // 通用 Root 执行（合并 stdout+stderr，兼容 Magisk/SuperSU 交互授权）
    //=========================================================================
    private fun runRoot(command: String): CommandResult {
        val output = StringBuilder()
        val exitCode: Int
        try {
            val process = ProcessBuilder("su")
                .redirectErrorStream(true)   // 合并输入流与错误流
                .start()

            process.outputStream.write("$command\n".toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            process.outputStream.close()

            process.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    output.appendLine(line)
                    line = reader.readLine()
                }
            }
            exitCode = process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
            return CommandResult("root 执行异常: ${e.message}", -1)
        }
        return CommandResult(output.toString(), exitCode)
    }

    //=========================================================================
    // 需求一：Root 静默安装 Assets 里的 APK
    // 从 assets/prebuilts/<apkName> 拷贝到 cacheDir，再 `pm install -r`
    //=========================================================================
    suspend fun installApkFromAssets(context: Context, apkName: String): String =
        withContext(Dispatchers.IO) {
            try {
                val dst = File(context.cacheDir, apkName)
                context.assets.open("$ASSET_PREBUILT_DIR/$apkName").use { input ->
                    FileOutputStream(dst).use { out -> input.copyTo(out) }
                }
                val r = runRoot("pm install -r ${dst.absolutePath}")
                if (r.exitCode == 0 && !r.output.lowercase().contains("failure")) {
                    "✅ 安装成功: $apkName"
                } else {
                    "❌ 安装失败: $apkName\n${r.output.trim()}"
                }
            } catch (e: Exception) {
                "❌ 安装失败: $apkName\n${e.message}"
            }
        }

    //=========================================================================
    // 需求二：将 Lawnchair 设为默认桌面
    // 动态解析当前安装的 Home Launcher 组件，避免硬编码 Activity
    //=========================================================================
    suspend fun setDefaultLauncher(context: Context): String =
        withContext(Dispatchers.IO) {
            val component = resolveLauncherComponent(context)
                ?: return@withContext "❌ 未找到已安装的 Lawnchair，请先安装"
            val r = runRoot("cmd package set-home-activity \"$component\"")
            if (r.exitCode == 0 && !r.output.lowercase().contains("exception")) {
                "✅ 已设置默认桌面: $component"
            } else {
                "❌ 设置默认桌面失败\n${r.output.trim()}"
            }
        }

    /**
     * 动态获取当前已安装 Launcher 的组件名（package/activity）。
     * 原理：查询 HOME category intent，匹配 Lawnchair 的包名。
     * 若用命令行手工获取：`adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
     */
    private fun resolveLauncherComponent(context: Context): String? {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        try {
            val resolveInfos = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
            for (ri in resolveInfos) {
                val pkg = ri.activityInfo.packageName
                if (pkg in LAWNCHAIR_PACKAGES) {
                    val cls = if (ri.activityInfo.name.startsWith(pkg)) ri.activityInfo.name else "$pkg.${ri.activityInfo.name}"
                    return "$pkg/$cls"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    //=========================================================================
    // 需求三：执行 Root 脚本并捕获完整输出（含权限容错）
    // script 传完整命令，如 "/data/local/chroot/start.sh start"
    //=========================================================================
    suspend fun executeRootScript(script: String): String =
        withContext(Dispatchers.IO) {
            val first = runRoot(script)

            // 判定是否需要容错：无有效输出 且 进程异常退出，或明确是权限/格式错误
            val needFallback =
                first.exitCode != 0 && (
                    first.output.isBlank() ||
                        first.output.contains("Permission denied", true) ||
                        first.output.contains("not executable", true) ||
                        first.output.contains("cannot execute", true) ||
                        first.output.contains("Exec format error", true)
                    )

            if (needFallback) {
                val ctx = appContext
                    ?: return@withContext (if (first.output.isBlank()) "脚本无有效输出 (exit=${first.exitCode})" else first.output.trim())
                try {
                    // 把脚本从不可执行位置（如 /sdcard）复制到 filesDir，chmod 755 后执行
                    val tokens = script.trim().split(Regex("\\s+"))
                    val scriptPath = tokens.first()
                    val args = tokens.drop(1).joinToString(" ")
                    val src = File(scriptPath)
                    val dst = File(ctx.filesDir, src.name)
                    src.copyTo(dst, overwrite = true)
                    runRoot("chmod 755 ${dst.absolutePath}")
                    val runCmd = buildString {
                        append("sh ").append(dst.absolutePath)
                        if (args.isNotEmpty()) { append(" ").append(args) }
                    }
                    runRoot(runCmd).output.trim()
                } catch (e: Exception) {
                    val base = if (first.output.isBlank()) "脚本无有效输出 (exit=${first.exitCode})" else first.output.trim()
                    "$base\n[容错] ${e.message}"
                }
            } else {
                if (first.output.isBlank()) "脚本无有效输出 (exit=${first.exitCode})" else first.output.trim()
            }
        }
}
