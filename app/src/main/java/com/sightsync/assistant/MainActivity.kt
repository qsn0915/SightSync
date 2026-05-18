package com.sightsync.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SightSyncApp()
        }
    }
}

@Composable
private fun SightSyncApp(viewModel: PermissionViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showingIntro by rememberSaveable { mutableStateOf(true) }
    var showingPrivacy by rememberSaveable { mutableStateOf(false) }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refresh(context)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refresh(context)
    }

    RefreshPermissionsOnResume(viewModel, context)

    LaunchedEffect(Unit) {
        delay(2000)
        showingIntro = false
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF115D5A),
            secondary = Color(0xFF3F705C),
            surface = Color(0xFFFBFAF5),
            background = Color(0xFFF2F5EF),
            onSurface = Color(0xFF14201D),
        ),
    ) {
        val screen = when {
            showingIntro -> "intro"
            showingPrivacy -> "privacy"
            else -> "guide"
        }

        Crossfade(
            targetState = screen,
            animationSpec = tween(durationMillis = 520),
            label = "SightSyncIntroTransition",
        ) { target ->
            when (target) {
                "intro" -> WelcomeIntroScreen()
                "privacy" -> PrivacyExplanationScreen(onBack = { showingPrivacy = false })
                else -> PermissionGuideScreen(
                    state = state,
                    onOpenPrivacy = { showingPrivacy = true },
                    onOpenAccessibilitySettings = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onRequestMicrophone = {
                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.refresh(context)
                        }
                    },
                    onOpenOverlaySettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun WelcomeIntroScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "Hi，welcome to SightSync",
                color = Color(0xFF14201D),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                ),
            )
        }
    }
}

@Composable
private fun PermissionGuideScreen(
    state: PermissionUiState,
    onOpenPrivacy: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFF4F7EF), Color(0xFFFBFAF5), Color(0xFFEAF2F0)),
                ),
            )
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(state, onOpenPrivacy)
            PermissionProgressStrip(state)
            PermissionCard(
                title = "开启无障碍服务",
                description = "允许 SightSync 读取当前界面并执行你确认过的基础操作。",
                granted = state.accessibilityEnabled,
                actionLabel = "打开无障碍设置",
                onClick = onOpenAccessibilitySettings,
            )
            PermissionCard(
                title = "开启麦克风权限",
                description = "用于连续聆听你对助手说的短语音指令。",
                granted = state.microphoneGranted,
                actionLabel = "允许麦克风",
                onClick = onRequestMicrophone,
            )
            PermissionCard(
                title = "开启通知权限",
                description = "连续聆听时通过系统通知保持可见，并提供停止入口。",
                granted = state.notificationGranted,
                actionLabel = "允许通知",
                onClick = onRequestNotifications,
            )
            PermissionCard(
                title = "开启悬浮窗权限",
                description = "在无障碍服务运行时显示 SightSync 连续聆听开关。",
                granted = state.overlayGranted,
                actionLabel = "打开悬浮窗设置",
                onClick = onOpenOverlaySettings,
            )
        }
    }
}

@Composable
private fun PrivacyExplanationScreen(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFFF4F7EF), Color(0xFFFBFAF5))),
            )
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEF8)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "隐私说明",
                        color = Color(0xFF101820),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 30.sp,
                            lineHeight = 34.sp,
                        ),
                    )
                    Text(
                        text = "SightSync 会在你主动唤起助手后，读取当前屏幕文本；当节点信息不足时，可能附带截图给 AI 服务处理。",
                        color = Color(0xFF29323A),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "开启连续聆听后，SightSync 会短段录音并转写你对助手说的指令；麦克风工作时系统通知保持可见。云端 AI API key 保存在后端代理中，不写入 Android App。",
                        color = Color(0xFF4D5963),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "密码、验证码、银行卡号等敏感字段会优先在本地脱敏；后续阶段会继续按纲领限制动作白名单和高风险确认。",
                        color = Color(0xFF4D5963),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF115D5A)),
                    ) {
                        Text("返回权限入口")
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshPermissionsOnResume(
    viewModel: PermissionViewModel,
    context: Context,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(context, viewModel) {
        viewModel.refresh(context)
    }

    DisposableEffect(lifecycleOwner, context, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun Header(
    state: PermissionUiState,
    onOpenPrivacy: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0D1B1B),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF0D1B1B), Color(0xFF173B36), Color(0xFF253128)),
                    ),
                )
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroStatusPill(state.allGranted)
            Text(
                text = "SightSync 权限引导",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 29.sp,
                    lineHeight = 33.sp,
                ),
            )
            Text(
                text = if (state.allGranted) {
                    "必需权限已开启。无障碍服务启动后，助手会进入连续聆听并用语音回应。"
                } else {
                    "先完成权限设置，再启动可随时停止的连续聆听语音助手。"
                },
                color = Color(0xFFE6EFE8),
                style = MaterialTheme.typography.bodyLarge,
            )
            ReadinessBar(state.allGranted)
            Button(
                onClick = onOpenPrivacy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White,
                ),
            ) {
                Text("隐私说明")
            }
        }
    }
}

@Composable
private fun HeroStatusPill(ready: Boolean) {
    val label = if (ready) "READY FOR VOICE" else "SETUP REQUIRED"
    val color = if (ready) Color(0xFFB7F0C5) else Color(0xFFFFD36A)

    Surface(
        color = Color.White.copy(alpha = 0.10f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = color,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

@Composable
private fun ReadinessBar(ready: Boolean) {
    val brush = if (ready) {
        Brush.horizontalGradient(listOf(Color(0xFFB7F0C5), Color(0xFF4EC19A)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFFFFD36A), Color(0xFFFF7A59)))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.dp)
            .padding(top = 1.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent,
            shape = RoundedCornerShape(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(Modifier.background(brush, RoundedCornerShape(3.dp))),
            )
        }
    }
}

@Composable
private fun PermissionProgressStrip(state: PermissionUiState) {
    val items = listOf(
        "无障碍" to state.accessibilityEnabled,
        "麦克风" to state.microphoneGranted,
        "通知" to state.notificationGranted,
        "悬浮窗" to state.overlayGranted,
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.82f),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { (label, granted) ->
                val background = if (granted) Color(0xFFE1F5E9) else Color(0xFFFFF0CD)
                val foreground = if (granted) Color(0xFF116149) else Color(0xFF7A4C00)
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = background,
                ) {
                    Text(
                        text = if (granted) "$label 已开启" else "$label 待开启",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                        color = foreground,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEF8)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFE3E8DF), RoundedCornerShape(8.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = Color(0xFF101820),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                StatusBadge(granted)
            }
            Text(
                text = description,
                color = Color(0xFF4D5963),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (granted) {
                OutlinedButton(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("查看设置")
                }
            } else {
                Button(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF115D5A)),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(granted: Boolean) {
    val background = if (granted) Color(0xFFE1F5E9) else Color(0xFFFFF0CD)
    val textColor = if (granted) Color(0xFF116149) else Color(0xFF7A4C00)
    val label = if (granted) "已开启" else "待开启"

    Surface(
        color = background,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = textColor,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        )
    }
}
