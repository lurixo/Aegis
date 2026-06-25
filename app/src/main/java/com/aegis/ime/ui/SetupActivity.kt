package com.aegis.ime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.aegis.ime.dict.ModelDownload
import com.aegis.ime.ui.theme.AegisTheme
import com.aegis.ime.user.UserModel
import java.io.File

/** Landing screen: enable the IME, switch to it, and a field to try typing. */
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AegisTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SetupScreen()
                }
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Aegis 输入法", style = MaterialTheme.typography.headlineMedium)
        Text(
            "离线中文 / 英文输入法。自建拼音引擎（全拼 + 九宫格 T9），模糊拼音、简拼、中英混输、" +
                "英文补全纠错、离线自学习；可选下载万象大模型增强。全程离线，输入不联网。",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("启用步骤", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("1 · 在系统设置中启用 Aegis") }
                Button(
                    onClick = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                            as InputMethodManager
                        imm.showInputMethodPicker()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("2 · 切换到 Aegis 输入法") }
            }
        }

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("3 · 在此试打") },
            modifier = Modifier.fillMaxWidth(),
        )

        SettingsCard()
        GramDownloadCard()
        UserDictCard()
    }
}

@Composable
private fun GramDownloadCard() {
    val context = LocalContext.current
    val dest = ModelDownload.destFile(context.filesDir)
    fun doneLabel() = "已下载（${dest.length() / 1048576} MB），下次切换到 Aegis 生效"

    var present by remember { mutableStateOf(dest.exists() && dest.length() > 1024) }
    var status by remember { mutableStateOf(if (present) doneLabel() else "未下载") }
    var progress by remember { mutableStateOf(0f) }
    var downloading by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("增强模型（万象离线大模型）", style = MaterialTheme.typography.titleMedium)
            Text(
                "可选下载 ~401 MB。下载后中文候选明显更准（内部评测 top-1 +约 9 分）；仅存本机，输入过程仍全程离线。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (downloading) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !downloading,
                    onClick = {
                        downloading = true
                        progress = 0f
                        status = "下载中…"
                        val handler = Handler(Looper.getMainLooper())
                        var lastPct = -1
                        Thread {
                            val ok = ModelDownload.download(ModelDownload.GRAM_URL, dest) { done, total ->
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) { lastPct = pct; handler.post { progress = pct / 100f } }
                                }
                            }
                            handler.post {
                                downloading = false
                                present = dest.exists() && dest.length() > 1024
                                status = if (ok) doneLabel() else "下载失败"
                            }
                        }.apply { isDaemon = true }.start()
                    },
                ) { Text("下载") }
                OutlinedButton(
                    enabled = !downloading && present,
                    onClick = { dest.delete(); present = false; progress = 0f; status = "未下载" },
                ) { Text("删除") }
            }
        }
    }
}

@Composable
private fun SettingsCard() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var fuzzy by remember { mutableStateOf(prefs.getBoolean("fuzzy", true)) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("输入设置", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("模糊拼音", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "容忍 zh/z、ch/c、sh/s、ang/an、eng/en、ing/in；下次切换到 Aegis 生效。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = fuzzy,
                    onCheckedChange = {
                        fuzzy = it
                        prefs.edit { putBoolean("fuzzy", it) }
                    },
                )
            }
        }
    }
}

@Composable
private fun UserDictCard() {
    val context = LocalContext.current
    val userDb = File(context.filesDir, "userdb.txt")

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null && userDb.exists()) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    userDb.inputStream().use { it.copyTo(out) }
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val tmp = File(context.cacheDir, "import_userdb.txt")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                val merged = UserModel().apply { if (userDb.exists()) load(userDb) }
                merged.importFrom(tmp, System.currentTimeMillis())
                merged.save(userDb)
                tmp.delete()
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("学习词库", style = MaterialTheme.typography.titleMedium)
            Text(
                "Aegis 会离线学习你常用的词与下一个词；数据只存本机。导入会在下次切换到 Aegis 时生效。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { exportLauncher.launch("aegis-userdb.txt") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导出学习词库") }
            Button(
                onClick = { importLauncher.launch(arrayOf("text/plain")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导入学习词库") }
        }
    }
}
