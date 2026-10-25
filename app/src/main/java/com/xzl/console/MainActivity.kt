package com.xzl.console

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ===== 主题配色：全屏深色极客风 =====
private val Bg = Color(0xFF121212)      // 背景
private val Comp = Color(0xFF1E1E1E)    // 组件底色
private val CompBorder = Color(0xFF2A2A2A)
private val Accent = Color(0xFF00D68F)  // 强调色（绿）
private val Danger = Color(0xFFFF3B4D)  // 红色（停止）
private val TextOn = Color(0xFFE8E8E8)
private val TextOff = Color(0xFF9E9E9E)

// ===== 可用 Linux 系统列表 =====
private val SYSTEMS = listOf("Ubuntu 24.04", "Debian 12", "Alpine 3.20")

// ===== 运行后端 =====
enum class RuntimeMode(val label: String) {
    CHROOT("Chroot"),
    QEMU("QEMU"),
}

// ===== Chroot 模式 =====
private const val CHROOT_SCRIPT = "/data/local/chroot/start.sh"  // 你的 chroot 启动脚本路径
private const val START_SUCCESS = "Start Success"   // 脚本成功启动后应打印该标记
private const val STOP_SUCCESS = "Stop Success"     // 脚本成功停止后应打印该标记

// ===== 状态管理 ViewModel =====
class ConsoleViewModel : ViewModel() {

    // 面板状态（Compose 状态，UI 直接读取即可实现响应式刷新）
    var selectedSystem by mutableStateOf("Debian 12")
        private set
    var isXZFace by mutableStateOf(true)      // true=桌面, false=纯终端
        private set
    var sshEnabled by mutableStateOf(false)
        private set
    var vncEnabled by mutableStateOf(false)
        private set
    var isRunning by mutableStateOf(false)
        private set

    // 运行后端：Chroot / QEMU
    var runtimeMode by mutableStateOf(RuntimeMode.CHROOT)
        private set

    // QEMU 选中的镜像
    var selectedImage by mutableStateOf(RootManager.availableImages.first())
        private set

    // 免 Root QEMU 日志流（转发自 RootManager）
    val qemuLogs: StateFlow<String> = RootManager.qemuLogs

    // Toast 消息队列：UI 通过 LaunchedEffect 消费
    private val _message = mutableStateOf<String?>(null)
    val message: String? get() = _message.value

    private var busy by mutableStateOf(false) // 防止重复点击

    fun onSystemSelect(sys: String) { selectedSystem = sys }
    fun onModeChange(desktop: Boolean) { isXZFace = desktop }
    fun onRuntimeModeChange(mode: RuntimeMode) { runtimeMode = mode }
    fun onImageSelect(image: String) { selectedImage = image }
    fun onSshChange(v: Boolean) { sshEnabled = v }
    fun onVncChange(v: Boolean) { vncEnabled = v }

    // 清除已消费的 Toast
    fun consumeMessage() { _message.value = null }

    // 主按钮点击：根据当前状态与运行后端启动/停止容器
    fun toggleContainer(context: Context) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val result = if (!isRunning) start(context) else stop(context)
            _message.value = result
            busy = false
        }
    }

    // 安装 Lawnchair / AVNC
    fun installApk(context: Context, apkName: String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            _message.value = RootManager.installApkFromAssets(context, apkName)
            busy = false
        }
    }

    // 设置默认桌面
    fun setDefaultHome(context: Context) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            _message.value = RootManager.setDefaultLauncher(context)
            busy = false
        }
    }

    private suspend fun start(context: Context): String =
        when (runtimeMode) {
            RuntimeMode.CHROOT -> {
                val out = RootManager.executeRootScript("$CHROOT_SCRIPT start")
                if (out.contains(START_SUCCESS)) { isRunning = true; "✅ Chroot 已启动" }
                else { isRunning = false; "❌ Chroot 启动失败\n$out" }
            }
            RuntimeMode.QEMU -> {
                val msg = RootManager.startQemuVM(context, selectedImage)
                isRunning = msg.contains("成功")
                msg
            }
        }

    private suspend fun stop(context: Context): String =
        when (runtimeMode) {
            RuntimeMode.CHROOT -> {
                val out = RootManager.executeRootScript("$CHROOT_SCRIPT stop")
                if (out.contains(STOP_SUCCESS)) { isRunning = false; "✅ Chroot 已停止" }
                else { isRunning = false; "❌ Chroot 停止失败\n$out" }
            }
            RuntimeMode.QEMU -> {
                val msg = RootManager.stopQemuVM()
                isRunning = false
                msg
            }
        }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RootManager.init(this) // 设置全局上下文，供 filesDir 容错使用
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                XzlConsoleApp()
            }
        }
    }
}

// ===== 单页根组件 =====
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XzlConsoleApp(viewModel: ConsoleViewModel = viewModel()) {
    val context = LocalContext.current
    var showSystemSheet by rememberSaveable { mutableStateOf(false) }
    var showImageSheet by rememberSaveable { mutableStateOf(false) }
    val logText by viewModel.qemuLogs.collectAsState()

    // 消费 ViewModel 里的 Toast 消息
    LaunchedEffect(viewModel.message) {
        viewModel.message?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

    val memUsed = if (viewModel.isRunning) "2.3G" else "0"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()   // 修复顶部栏被状态栏遮挡
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // ========== 1. 顶部栏 ==========
        TopBar()

        Spacer(Modifier.height(20.dp))

        // ========== 2. 配置面板 ==========
        ConfigPanel(
            selectedSystem = viewModel.selectedSystem,
            isXZFace = viewModel.isXZFace,
            runtimeMode = viewModel.runtimeMode,
            selectedImage = viewModel.selectedImage,
            sshEnabled = viewModel.sshEnabled,
            vncEnabled = viewModel.vncEnabled,
            onDropdownClick = { showSystemSheet = true },
            onModeChange = viewModel::onModeChange,
            onRuntimeModeChange = viewModel::onRuntimeModeChange,
            onImageDropdownClick = { showImageSheet = true },
            onSshChange = viewModel::onSshChange,
            onVncChange = viewModel::onVncChange,
        )

        // ========== 3. 状态显示区（居中） ==========
        StatusArea(isRunning = viewModel.isRunning, memUsed = memUsed)

        // ========== 4. 核心主按钮 ==========
        MainActionButton(
            isRunning = viewModel.isRunning,
            onClick = { viewModel.toggleContainer(context) },
        )

        Spacer(Modifier.height(16.dp))

        // ========== 4.5 QEMU 终端日志面板 ==========
        if (viewModel.runtimeMode == RuntimeMode.QEMU) {
            QemuLogPanel(text = logText)
            Spacer(Modifier.height(16.dp))
        }

        // ========== 4.6 工具按钮（Root 安装/设默认桌面） ==========
        ToolRow(
            onInstallLawnchair = { viewModel.installApk(context, RootManager.LAWNCHAIR_APK) },
            onInstallAvnc = { viewModel.installApk(context, RootManager.AVNC_APK) },
            onSetHome = { viewModel.setDefaultHome(context) },
        )

        Spacer(Modifier.height(24.dp))

        // ========== 5. 底部快捷工具栏 ==========
        QuickToolbar(enabled = viewModel.isRunning)
    }

    // ===== 系统选择 ModalBottomSheet =====
    if (showSystemSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSystemSheet = false },
            containerColor = Comp,
        ) {
            Column(Modifier.padding(bottom = 32.dp)) {
                Text(
                    "选择 Linux 系统",
                    color = TextOn,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                SYSTEMS.forEach { sys ->
                    val isSelected = sys == viewModel.selectedSystem
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onSystemSelect(sys); showSystemSheet = false }
                            .background(if (isSelected) Accent.copy(alpha = 0.12f) else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        Text(
                            sys,
                            color = if (isSelected) Accent else TextOn,
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }

    // ===== 镜像选择 ModalBottomSheet =====
    if (showImageSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImageSheet = false },
            containerColor = Comp,
        ) {
            Column(Modifier.padding(bottom = 32.dp)) {
                Text(
                    "选择 QEMU 镜像",
                    color = TextOn,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                RootManager.availableImages.forEach { img ->
                    val isSelected = img == viewModel.selectedImage
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onImageSelect(img); showImageSheet = false }
                            .background(if (isSelected) Accent.copy(alpha = 0.12f) else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        Text(
                            img,
                            color = if (isSelected) Accent else TextOn,
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

// ========== 1. 顶部栏 ==========
@Composable
private fun TopBar() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "XZL 控制中枢",
            color = TextOn,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = { /* 设置入口 */ }) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "设置",
                tint = TextOff,
            )
        }
    }
}

// ========== 2. 配置面板 ==========
@Composable
private fun ConfigPanel(
    selectedSystem: String,
    isXZFace: Boolean,
    runtimeMode: RuntimeMode,
    selectedImage: String,
    sshEnabled: Boolean,
    vncEnabled: Boolean,
    onDropdownClick: () -> Unit,
    onModeChange: (Boolean) -> Unit,
    onRuntimeModeChange: (RuntimeMode) -> Unit,
    onImageDropdownClick: () -> Unit,
    onSshChange: (Boolean) -> Unit,
    onVncChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Comp),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            // 顶部：当前目标 + 下拉箭头
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "当前目标: $selectedSystem",
                    color = TextOn,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                IconButton(onClick = onDropdownClick) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "切换系统",
                        tint = Accent,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 中间：模式单选
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeRadio(
                    label = "纯终端",
                    selected = !isXZFace,
                    onSelect = { onModeChange(false) },
                )
                Spacer(Modifier.width(24.dp))
                ModeRadio(
                    label = "XZFace 桌面",
                    selected = isXZFace,
                    onSelect = { onModeChange(true) },
                )
            }

            Spacer(Modifier.height(16.dp))

            // 运行后端：Chroot / QEMU
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "运行后端",
                    color = TextOff,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(end = 16.dp),
                )
                ModeRadio(
                    label = "Chroot",
                    selected = runtimeMode == RuntimeMode.CHROOT,
                    onSelect = { onRuntimeModeChange(RuntimeMode.CHROOT) },
                )
                Spacer(Modifier.width(8.dp))
                ModeRadio(
                    label = "QEMU",
                    selected = runtimeMode == RuntimeMode.QEMU,
                    onSelect = { onRuntimeModeChange(RuntimeMode.QEMU) },
                )
            }

            Spacer(Modifier.height(16.dp))

            // QEMU 镜像选择
            if (runtimeMode == RuntimeMode.QEMU) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "镜像: $selectedImage",
                        color = TextOn,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    IconButton(onClick = onImageDropdownClick) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "切换镜像",
                            tint = Accent,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // 底部：两个开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ToggleRow("SSH 服务", sshEnabled, onSshChange)
                ToggleRow("VNC 远程", vncEnabled, onVncChange)
            }
        }
    }
}

@Composable
private fun ModeRadio(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onSelect)) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = Accent,
                unselectedColor = TextOff,
            ),
        )
        Text(label, color = if (selected) TextOn else TextOff, fontSize = 15.sp)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Accent,
                checkedTrackColor = Accent.copy(alpha = 0.35f),
                uncheckedThumbColor = TextOff,
                uncheckedTrackColor = CompBorder,
            ),
        )
        Text(label, color = TextOff, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

// ========== 3. 状态显示区 ==========
@Composable
private fun StatusArea(isRunning: Boolean, memUsed: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (isRunning) "运行中" else "已停止",
            color = if (isRunning) Accent else TextOff,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "内存：$memUsed / 4G",
            color = TextOff,
            fontSize = 14.sp,
        )
    }
}

// ========== 4. 核心主按钮 ==========
@Composable
private fun MainActionButton(isRunning: Boolean, onClick: () -> Unit) {
    val label = if (isRunning) "⏹ 停止容器" else "▶️ 启动容器"
    val color = if (isRunning) Danger else Accent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color(0xFF121212),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

// ========== 4.5 QEMU 终端日志面板 ==========
@Composable
private fun QemuLogPanel(text: String) {
    val scrollState = rememberScrollState()
    // 实时日志时自动滚动到底部
    LaunchedEffect(text) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Comp)
            .padding(12.dp),
    ) {
        Text(
            "终端日志",
            color = Accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .verticalScroll(scrollState),
        ) {
            Text(
                if (text.isBlank()) "「暂无日志」\nQEMU 启动后此处会实时回显输出" else text,
                color = TextOn,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ========== 4.6 工具按钮 ==========
@Composable
private fun ToolRow(
    onInstallLawnchair: () -> Unit,
    onInstallAvnc: () -> Unit,
    onSetHome: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ToolButton(label = "安装桌面", onClick = onInstallLawnchair, modifier =Modifier.weight(1f))
        ToolButton(label = "安装 AVNC", onClick = onInstallAvnc, modifier=Modifier.weight(1f))
        ToolButton(label = "设为默认", onClick = onSetHome, modifier=Modifier.weight(1f))
    }
}

@Composable
private fun ToolButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CompBorder)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ========== 5. 底部快捷工具栏 ==========
@Composable
private fun QuickToolbar(enabled: Boolean) {
    val bg = if (enabled) Comp else CompBorder.copy(alpha = 0.4f)
    val fg = if (enabled) TextOn else TextOff.copy(alpha = 0.6f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuickButton(label = "打开终端", enabled = enabled, bg = bg, fg = fg, modifier =Modifier.weight(1f), onClick = {})
        QuickButton(label = "连接 XZFace", enabled = enabled, bg = bg, fg = fg, modifier =Modifier.weight(1f), onClick = {})
    }
}

@Composable
private fun QuickButton(
    label: String,
    enabled: Boolean,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
