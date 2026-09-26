package dev.deeplinks.native

import dev.deeplinks.core.DshType

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.deeplinks.core.AppSettingsStore
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.Host
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshSheetGrabber
import dev.deeplinks.native.util.SessionFilter
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.catalogKind
import dev.deeplinks.native.util.sessionShowsRefreshBanner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- Agent 预设选择（新会话 compose 阶段） ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPresetPickerSheet(
    presets: List<MobileAgentPreset>,
    currentId: String,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Dsh.bgCard,
        contentColor = Dsh.labelPrimary,
        shape = DshSheetShape,
        dragHandle = null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val selectedPreset = presets.firstOrNull { it.id == currentId }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            DshSheetGrabber()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    selectedPreset?.name?.ifBlank { L.defaultHarnessPreset } ?: L.defaultHarnessPreset,
                    color = Dsh.labelPrimary,
                    style = DshType.headline,
                    fontWeight = FontWeight(600),
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                if (selectedPreset != null) {
                    Icon(
                        CheckOutline14,
                        contentDescription = null,
                        tint = Dsh.brand400,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                L.chooseAgentPresetDesc,
                color = Dsh.labelTertiary,
                style = DshType.t13x18,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(14.dp))
            val presetKind = catalogKind(
                hasItems = presets.isNotEmpty(),
                initialLoad = loading && presets.isEmpty(),
                hasError = error != null,
            )
            when (presetKind) {
                SessionListKind.Loading -> Text(L.loadingPresets, color = Dsh.labelTertiary, style = DshType.t13)
                SessionListKind.Error -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        error ?: L.noAgentPresets,
                        color = Dsh.error,
                        style = DshType.titleSmall,
                    )
                    Text(
                        L.retry,
                        color = Dsh.brand400,
                        style = DshType.t14M,
                        fontWeight = FontWeight(500),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(DshRadius.md))
                            .semantics {
                                role = Role.Button
                                contentDescription = L.retry
                            }
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                SessionListKind.Empty -> Text(L.noAgentPresets, color = Dsh.labelTertiary, style = DshType.t13)
                SessionListKind.Content -> {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (sessionShowsRefreshBanner(presets.isNotEmpty(), error != null)) {
                        item(key = "presets-refresh") {
                            LoadOlderRow(
                                loading = false,
                                failed = true,
                                failedMessage = error,
                                onClick = onRetry,
                            )
                        }
                    }
                    items(presets, key = { it.id }) { preset ->
                        val selected = preset.id == currentId
                        val title = preset.name.ifBlank { preset.id }
                        val desc = preset.description.ifBlank { "" }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                            .clip(RoundedCornerShape(DshRadius.md))
                            .background(if (selected) Dsh.bgSelected else Color.Transparent)
                            .heightIn(min = 48.dp)
                            .clickable { onSelect(preset.id) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    title,
                                    color = Dsh.labelPrimary,
                                    style = DshType.t15x20M,
                                    fontWeight = FontWeight(500),
                                    lineHeight = 20.sp,
                                )
                                if (desc.isNotBlank()) {
                                    Text(
                                        desc,
                                        color = Dsh.labelTertiary,
                                        style = DshType.t12x17,
                                        lineHeight = 17.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (selected) {
                                Spacer(Modifier.width(10.dp))
                                Icon(
                                    CheckOutline14,
                                    contentDescription = null,
                                    tint = Dsh.brand400,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(16.dp),
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

// ---------- 权限选择弹层（WI-004：真实写入服务端 permission.defaultPreset） ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PermissionPickerSheet(
    context: android.content.Context,
    host: Host,
    currentPreset: String,
    sessionId: String? = null,
    onSaved: (AppSettings) -> Unit,
    onSessionPreset: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(currentPreset) }
    var showFullAccessConfirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun apply(preset: String) {
        if (saving) return
        saving = true
        error = null
        scope.launch(Dispatchers.IO) {
            try {
                if (sessionId != null) {
                    MobileApiClient(host).setSessionPermission(sessionId, preset)
                    withContext(Dispatchers.Main) {
                        saving = false
                        onSessionPreset(preset)
                        onDismiss()
                    }
                } else {
                    AppSettingsStore.save(host, context, "permission", org.json.JSONObject().put("defaultPreset", preset))
                    withContext(Dispatchers.Main) {
                        saving = false
                        onSaved(AppSettingsStore.cached(context, host))
                        onDismiss()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    saving = false
                    error = e.message ?: L.saveFailed
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = Dsh.bgCard,
        contentColor = Dsh.labelPrimary,
        shape = DshSheetShape,
        dragHandle = null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            DshSheetGrabber()
            Text(L.accessMode, color = Dsh.labelPrimary, style = DshType.headline, fontWeight = FontWeight(600), lineHeight = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                if (sessionId != null) L.currentSessionPermissionDesc else L.defaultPermissionDesc,
                color = Dsh.labelTertiary,
                style = DshType.titleSmall,
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PermissionModeOption(
                    title = L.permReadOnly,
                    desc = L.readOnlyPermissionDesc,
                    sub = L.permReadOnlySub,
                    accent = Dsh.labelSecondary,
                    icon = BrowseOutline16,
                    selected = selected == "read-only",
                    enabled = !saving,
                    onClick = {
                        selected = "read-only"
                        apply("read-only")
                    }
                )
                PermissionModeOption(
                    title = L.permWorkspaceWrite,
                    desc = L.workspaceWritePermissionDesc,
                    sub = L.permWorkspaceWriteSub,
                    accent = Dsh.brand400,
                    icon = FolderOpenOutline16,
                    selected = selected == "workspace-write",
                    enabled = !saving,
                    onClick = {
                        selected = "workspace-write"
                        apply("workspace-write")
                    }
                )
                PermissionModeOption(
                    title = L.permFullAccess,
                    desc = L.fullAccessPermissionDesc,
                    sub = L.permFullAccessSub,
                    accent = Dsh.warn,
                    icon = WarningOutline16,
                    selected = selected == "danger-full-access",
                    enabled = !saving,
                    onClick = {
                        selected = "danger-full-access"
                        showFullAccessConfirm = true
                    }
                )
            }
            if (saving) {
                Spacer(Modifier.height(10.dp))
                Text(L.saving, color = Dsh.labelTertiary, style = DshType.t12)
            }
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.saveFailedWithMessage.format(error), color = Dsh.error, style = DshType.t12, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(DshRadius.sm))
                            .background(Dsh.bgTrack)
                            .clickable(enabled = !saving) { apply(selected) }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(L.retry, color = Dsh.brand400, style = DshType.t12M, fontWeight = FontWeight(500))
                    }
                }
            }
        }
    }

    // Full access 确认
    if (showFullAccessConfirm) {
        val motion = dialogMotionState { showFullAccessConfirm = false }
        // 关闭入口统一先走出场动画，播完才回调（翻转显示状态）
        val requestDismiss = motion.requestDismiss
        Dialog(
            onDismissRequest = requestDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = motion.alpha.value }
                    .background(Dsh.bgOverlay)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = requestDismiss),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(0.9f)
                        .navigationBarsPadding()
                        .graphicsLayer {
                            alpha = motion.alpha.value
                            scaleX = motion.scale.value
                            scaleY = motion.scale.value
                        }
                        .clip(RoundedCornerShape(DshRadius.dialog))
                        .background(Dsh.bgCard)
                        .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.dialog))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .padding(18.dp)
                ) {
                    Text(L.confirmFullAccessTitle, color = Dsh.labelPrimary, style = DshType.t15x21M, fontWeight = FontWeight(500), lineHeight = 21.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        L.confirmFullAccessMessage,
                        color = Dsh.labelTertiary,
                        style = DshType.captionRelaxed,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = requestDismiss,
                            colors = ButtonDefaults.textButtonColors(contentColor = Dsh.labelSecondary),
                        ) {
                            Text(L.cancel, style = DshType.t12M, fontWeight = FontWeight(500))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                // 先走出场动画（播完回调才翻转显示状态），高危授权立即执行
                                requestDismiss()
                                apply("danger-full-access")
                            },
                            enabled = !saving,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Dsh.error,
                                contentColor = Dsh.onBrand,
                                disabledContainerColor = Dsh.bgTrack,
                                disabledContentColor = Dsh.labelTertiary,
                            ),
                        ) {
                            Text(L.enableFullAccess, style = DshType.t12M, fontWeight = FontWeight(500))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PermissionModeOption(
    title: String,
    desc: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    sub: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(
                when {
                    selected -> accent.copy(alpha = 0.08f)
                    pressed -> Dsh.pressed
                    else -> Dsh.bgTrack
                }
            )
            .border(
                1.dp,
                if (selected) accent.copy(alpha = 0.4f) else Dsh.borderSubtle,
                RoundedCornerShape(DshRadius.lg)
            )
            .clickable(interactionSource = interaction, indication = dshRipple(), enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(DshRadius.md))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = Dsh.labelPrimary,
                    style = DshType.t15x20M,
                    fontWeight = FontWeight(500),
                    lineHeight = 20.sp
                )
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(DshRadius.full))
                            .background(accent.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = L.currentLabel,
                            style = DshType.microRelaxed,
                            color = accent,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(desc, color = Dsh.labelTertiary, style = DshType.caption, lineHeight = 16.sp)
            if (sub != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = sub,
                    color = accent.copy(alpha = 0.85f),
                    style = DshType.microRelaxed,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(1.5.dp, if (selected) accent else Dsh.borderStrong, CircleShape)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(accent)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionFilterSheet(
    selected: SessionFilter,
    counts: Map<SessionFilter, Int>,
    onSelect: (SessionFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Dsh.bgCard,
        contentColor = Dsh.labelPrimary,
        shape = DshSheetShape,
        dragHandle = null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            DshSheetGrabber()
            Text(L.filterSessions, color = Dsh.labelPrimary, style = DshType.headline, fontWeight = FontWeight(600), lineHeight = 24.sp)
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionFilter.entries.forEach { f ->
                    val label = when (f) {
                        SessionFilter.ALL -> L.allSessions
                        SessionFilter.RUNNING -> L.runningStatus
                        SessionFilter.STOPPED -> L.stopped
                    }
                    PermissionModeOption(
                        title = "$label · ${counts[f] ?: 0}",
                        desc = when (f) {
                            SessionFilter.ALL -> L.showAllSessions
                            SessionFilter.RUNNING -> L.onlyShowRunningSessions
                            SessionFilter.STOPPED -> L.onlyShowStoppedSessions
                        },
                        accent = Dsh.labelSecondary,
                        icon = ChecklistOutline14,
                        selected = f == selected,
                        enabled = true,
                        onClick = {
                            onSelect(f)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}