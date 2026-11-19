package com.readvocab.app.ui.settings

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.readvocab.app.AppContainer
import com.readvocab.app.data.AppSettings
import com.readvocab.app.data.BackupException
import com.readvocab.app.data.BackupManager
import com.readvocab.app.reminder.Reminders
import com.readvocab.app.ui.Async
import com.readvocab.app.ui.appViewModel
import com.readvocab.core.ai.AiConfig
import com.readvocab.core.ai.AiException
import com.readvocab.core.ai.AiProviders
import com.readvocab.core.ai.AiTutor
import com.readvocab.core.ai.AnthropicProvider
import com.readvocab.core.ai.ProviderType
import com.readvocab.core.vocab.HighlightMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings
    val test = MutableStateFlow<Async<String>>(Async.Idle)
    val backup = MutableStateFlow<Async<String>>(Async.Idle)
    val lastBackupAt = MutableStateFlow(c.backup.lastBackupAt)
    /** Set after a successful restore; the screen then restarts the app. */
    val restored = MutableStateFlow(false)

    fun exportBackup(uri: Uri) = runBackup {
        val r = c.backup.export(uri)
        lastBackupAt.value = r.createdAt
        "已备份 ${r.books} 本书、${r.words} 个词的记录"
    }

    fun restoreBackup(uri: Uri) = runBackup {
        c.backup.restore(uri)
        restored.value = true
        "恢复完成，正在重启…"
    }

    private fun runBackup(block: suspend () -> String) {
        backup.value = Async.Loading
        viewModelScope.launch {
            backup.value = try {
                Async.Done(block())
            } catch (e: BackupException) {
                Async.Failed(e.message ?: "操作失败")
            } catch (e: Exception) {
                Async.Failed("操作失败：${e.message}")
            }
        }
    }

    fun update(f: (AppSettings) -> AppSettings) { c.settings.update(f); test.value = Async.Idle }

    fun testConnection() {
        val config = c.settings.current.aiConfig
        if (!config.isUsable) { test.value = Async.Failed("请先填写 API Key 和模型"); return }
        test.value = Async.Loading
        viewModelScope.launch {
            test.value = try {
                val reply = withContext(Dispatchers.IO) { AiTutor(AiProviders.create(config)).ping() }
                Async.Done("连接成功（模型回复：${reply.take(30)}）")
            } catch (e: AiException) {
                Async.Failed(e.message ?: "连接失败")
            } catch (e: Exception) {
                Async.Failed("连接失败：${e.message}")
            }
        }
    }
}

private val OPENAI_PRESETS = listOf(
    Triple("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    Triple("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    Triple("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    Triple("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onStartTest: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val backup by vm.backup.collectAsStateWithLifecycle()
    val lastBackupAt by vm.lastBackupAt.collectAsStateWithLifecycle()
    val restored by vm.restored.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current

    LaunchedEffect(restored) { if (restored) BackupManager.restartApp(context) }

    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.update { it.copy(reminderEnabled = granted) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore = uri
    }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AI 助手", style = MaterialTheme.typography.titleMedium)
            Text("用于语境释义和整句解析。Key 只保存在本机，请求直接发往你选择的服务商。", style = MaterialTheme.typography.bodySmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ProviderType.entries.forEachIndexed { i, type ->
                    SegmentedButton(
                        selected = s.aiType == type,
                        onClick = {
                            if (s.aiType != type) vm.update { it.copy(aiType = type, model = AiConfig.defaultModel(type), baseUrl = "") }
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, ProviderType.entries.size),
                    ) { Text(if (type == ProviderType.ANTHROPIC) "Claude" else "OpenAI 兼容") }
                }
            }
            if (s.aiType == ProviderType.OPENAI_COMPATIBLE) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OPENAI_PRESETS.forEach { (name, url, model) ->
                        SuggestionChip(onClick = { vm.update { it.copy(baseUrl = url, model = model) } }, label = { Text(name) })
                    }
                }
            }
            OutlinedTextField(
                value = s.apiKey,
                onValueChange = { v -> vm.update { it.copy(apiKey = v.trim()) } },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "显示") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.model,
                onValueChange = { v -> vm.update { it.copy(model = v.trim()) } },
                label = { Text("模型") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (s.aiType == ProviderType.ANTHROPIC) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnthropicProvider.SUGGESTED_MODELS.forEach { m ->
                        SuggestionChip(onClick = { vm.update { it.copy(model = m) } }, label = { Text(m) })
                    }
                }
                Text("查词这类短任务用 claude-haiku-5-5 最省钱；opus 质量最高但更贵。", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = s.baseUrl,
                onValueChange = { v -> vm.update { it.copy(baseUrl = v.trim()) } },
                label = { Text("接口地址（可选）") },
                placeholder = { Text(AiConfig.defaultBaseUrl(s.aiType)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::testConnection, enabled = test != Async.Loading) { Text(if (test == Async.Loading) "测试中…" else "测试连接") }
                when (val t = test) {
                    is Async.Done -> Text(t.value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    is Async.Failed -> Text(t.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
            }
            SwitchRow("点词时自动请求 AI 语境释义", "关闭时需要在查词卡片里手动点一下，更省额度", s.autoAi) { v -> vm.update { it.copy(autoAi = v) } }

            SwitchRow(
                "每天自动生成复习短文",
                "打开 App 时，如果到期的词达到 8 个、今天还没生成过，就用它们写一篇短文放进书架",
                s.autoStory,
            ) { v -> vm.update { it.copy(autoStory = v) } }

            HorizontalDivider()
            Text("阅读", style = MaterialTheme.typography.titleMedium)
            SwitchRow("只高亮学习中的词", "关闭时，超出你词汇量的生词也会加下划线", s.highlightMode == HighlightMode.LEARNING_ONLY) { v ->
                vm.update { it.copy(highlightMode = if (v) HighlightMode.LEARNING_ONLY else HighlightMode.ALL) }
            }
            SwitchRow(
                "读过 3 次未查的生词自动记为认识",
                "让词汇模型随阅读自我修正",
                s.autoKnownAfter > 0,
            ) { v -> vm.update { it.copy(autoKnownAfter = if (v) 3 else 0) } }

            HorizontalDivider()
            Text("每日提醒", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                "每天提醒我复习和阅读",
                "有到期的词时提醒复习；没有的话，当天还没读过才提醒",
                s.reminderEnabled,
            ) { v ->
                if (v && !Reminders.canNotify(context) && Build.VERSION.SDK_INT >= 33) {
                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    vm.update { it.copy(reminderEnabled = v) }
                }
            }
            if (s.reminderEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("提醒时间", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { pickTime = true }) {
                        Text("%02d:%02d".format(s.reminderMinuteOfDay / 60, s.reminderMinuteOfDay % 60))
                    }
                }
            }

            HorizontalDivider()
            Text("数据备份", style = MaterialTheme.typography.titleMedium)
            Text(
                "书籍、生词、复习记录和阅读设置都只保存在这台手机上。备份成一个文件，存到网盘或电脑，换手机时可以恢复。备份里不含 API Key。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (lastBackupAt > 0) "上次备份：${formatDateTime(lastBackupAt)}" else "还没有备份过",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { exportLauncher.launch(BackupManager.defaultFileName()) }, enabled = backup != Async.Loading) { Text("备份到文件") }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = backup != Async.Loading) {
                    Text("从备份恢复")
                }
            }
            when (val b = backup) {
                Async.Loading -> Text("处理中…", style = MaterialTheme.typography.bodySmall)
                is Async.Done -> Text(b.value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                is Async.Failed -> Text(b.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else -> Unit
            }

            HorizontalDivider()
            Text("词汇量", style = MaterialTheme.typography.titleMedium)
            Text(
                if (s.vocabSize > 0) "测试结果约 ${s.vocabSize} 词。文章难度按你认识各个词频段单词的比例来估算；你查过、学过的词按你的实际记录算。"
                else "还没测试。目前按大约 ${s.knownBoundary} 词的水平估算文章难度，测一下会准得多。",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (s.calibrationCount > 0) {
                val shift = s.difficultyShift
                Text(
                    "已根据你 ${s.calibrationCount} 次读后反馈校准：" + when {
                        shift > 0.05f -> "你觉得文章比估算的难，估算已相应调难。"
                        shift < -0.05f -> "你觉得文章比估算的容易，估算已相应调易。"
                        else -> "估算和你的感受基本一致。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onStartTest) { Text(if (s.vocabSize > 0) "重新测试" else "开始测试") }

            HorizontalDivider()
            Text("词典数据来自开源项目 ECDICT（MIT 许可）。", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (pickTime) {
        val state = rememberTimePickerState(s.reminderMinuteOfDay / 60, s.reminderMinuteOfDay % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("提醒时间") },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    vm.update { it.copy(reminderMinuteOfDay = state.hour * 60 + state.minute) }
                    pickTime = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("取消") } },
        )
    }
    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("从备份恢复？") },
            text = { Text("这台手机上现有的书籍、生词和复习记录会被备份里的内容替换。AI 设置和 API Key 保持不变。恢复后 App 会自动重启。") },
            confirmButton = { TextButton(onClick = { vm.restoreBackup(uri); confirmRestore = null }) { Text("恢复") } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("取消") } },
        )
    }
}

private fun formatDateTime(ms: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles, not just the small switch.
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
