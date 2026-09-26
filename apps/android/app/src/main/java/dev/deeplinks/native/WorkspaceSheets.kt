package dev.deeplinks.native

import dev.deeplinks.core.DshType

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshSheetGrabber
import dev.deeplinks.native.util.abbreviateHomePath
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.visibleUserWorkspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- 添加工作区 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddWorkspaceSheet(
    creationAnchor: MobileWorkspace?,
    onDismiss: () -> Unit,
    createWorkspace: (String, String?) -> MobileWorkspaceCreation,
    onCreated: (MobileWorkspace) -> Unit,
    onAuthExpired: (Throwable) -> Unit,
) {
    val sheetScope = rememberCoroutineScope()
    var workspaceInput by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val trimmedInput = workspaceInput.trim()
    val isAbsolutePath = trimmedInput.startsWith("/")
    val anchorLabel = creationAnchor?.title?.takeIf { it.isNotBlank() }
        ?: creationAnchor?.path?.trimEnd('/', '\\')?.substringAfterLast('/')
        ?: ""
    val canSubmit = trimmedInput.isNotBlank() && !submitting && (isAbsolutePath || creationAnchor != null)

    ModalBottomSheet(
        onDismissRequest = { if (!submitting) onDismiss() },
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
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            DshSheetGrabber()
            Text(
                L.addWorkspace,
                color = Dsh.labelPrimary,
                style = DshType.headline,
                fontWeight = FontWeight(600),
                lineHeight = 24.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(L.addWorkspaceDesc, color = Dsh.labelTertiary, style = DshType.bodyDense, lineHeight = 20.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = workspaceInput,
                onValueChange = {
                    workspaceInput = it
                    submitError = null
                },
                enabled = !submitting,
                singleLine = true,
                label = { Text(L.workspaceNameOrPath) },
                placeholder = { Text(L.workspacePathExample) },
                leadingIcon = {
                    Icon(
                        Icons.Default.CreateNewFolder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                isError = submitError != null,
                supportingText = {
                    val err = submitError
                    val supporting = when {
                        err != null -> L.addWorkspaceFailed.format(err)
                        isAbsolutePath -> L.workspaceRegisterExistingPath
                        creationAnchor != null -> L.workspaceCreateNextTo.format(anchorLabel)
                        else -> L.workspaceNameRequiresAnchor
                    }
                    Text(supporting, style = DshType.t12x17, lineHeight = 17.sp)
                },
                textStyle = DshType.t14.copy(color = Dsh.labelPrimary),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Dsh.labelPrimary,
                    unfocusedTextColor = Dsh.labelPrimary,
                    disabledTextColor = Dsh.labelTertiary,
                    focusedContainerColor = Dsh.bgInput,
                    unfocusedContainerColor = Dsh.bgInput,
                    disabledContainerColor = Dsh.bgInput.copy(alpha = 0.6f),
                    cursorColor = Dsh.brand400,
                    focusedBorderColor = Dsh.brand400,
                    unfocusedBorderColor = Dsh.borderSubtle,
                    disabledBorderColor = Dsh.borderSubtle,
                    errorBorderColor = Dsh.error,
                    focusedLabelColor = Dsh.brand400,
                    unfocusedLabelColor = Dsh.labelTertiary,
                    errorLabelColor = Dsh.error,
                    focusedLeadingIconColor = Dsh.brand400,
                    unfocusedLeadingIconColor = Dsh.labelTertiary,
                    errorLeadingIconColor = Dsh.error,
                    focusedSupportingTextColor = Dsh.labelTertiary,
                    unfocusedSupportingTextColor = Dsh.labelTertiary,
                    errorSupportingTextColor = Dsh.error,
                ),
                shape = RoundedCornerShape(DshRadius.lg),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(DshRadius.lg))
                    .background(if (canSubmit) Dsh.brand400 else Dsh.buttonElevated.copy(alpha = 0.5f))
                    .clickable(enabled = canSubmit) {
                        val requestedInput = workspaceInput.trim()
                        val parentWorkspaceId = if (requestedInput.startsWith("/")) null else creationAnchor?.workspaceId
                        submitting = true
                        submitError = null
                        sheetScope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    createWorkspace(requestedInput, parentWorkspaceId)
                                }
                                submitting = false
                                onCreated(result.workspace)
                            } catch (error: Exception) {
                                submitting = false
                                if (isMobileAuthFailure(error)) {
                                    onAuthExpired(error)
                                } else {
                                    submitError = error.message?.takeIf { it.isNotBlank() } ?: L.unknownError
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (submitting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Dsh.labelTertiary,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            L.addingWorkspace,
                            color = Dsh.labelTertiary,
                            style = DshType.t13M,
                            fontWeight = FontWeight(500),
                        )
                    }
                } else {
                    Text(
                        if (isAbsolutePath) L.addExistingWorkspace else L.createAndAddWorkspace,
                        color = if (canSubmit) Dsh.onBrand else Dsh.labelTertiary,
                        style = DshType.t14SB,
                        fontWeight = FontWeight(600),
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacePickerSheet(
    sessions: List<MobileSession>,
    deletedWorkspaces: Set<String> = emptySet(),
    registeredPaths: List<String> = emptyList(),
    registryReady: Boolean = false,
    selectedPath: String? = null,
    catalogKind: SessionListKind = SessionListKind.Content,
    catalogError: String? = null,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val workspaces = remember(sessions, deletedWorkspaces, registeredPaths, registryReady) {
        visibleUserWorkspaces(
            sessionCwds = sessions.map { it.cwd },
            deletedWorkspaces = deletedWorkspaces,
            registeredPaths = registeredPaths,
            // 与 Web 对齐：注册表就绪后不再用历史会话 cwd 撑出幽灵工作区
            requireRegistered = registryReady,
        )
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(workspaces, query) {
        if (query.isBlank()) workspaces
        else workspaces.filter {
            it.contains(query, ignoreCase = true) || it.substringAfterLast('/').contains(query, ignoreCase = true)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            DshSheetGrabber()
            Text(L.chooseWorkspaceTitle, color = Dsh.labelPrimary, style = DshType.headline, fontWeight = FontWeight(600), lineHeight = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text(L.chooseWorkspaceDesc, color = Dsh.labelTertiary, style = DshType.t13x18, lineHeight = 18.sp)
            if (workspaces.size > 6) {
                Spacer(Modifier.height(12.dp))
                SheetSearchField(value = query, onValueChange = { query = it }, placeholder = L.searchWorkspace)
            }
            Spacer(Modifier.height(14.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                WorkspaceOptionRow(
                    title = L.ungrouped,
                    path = L.noWorkspaceBinding,
                    selected = selectedPath == null,
                    onClick = {
                        onDismiss()
                        onPick(null)
                    }
                )
                when (catalogKind) {
                    SessionListKind.Loading -> Text(
                        L.loading,
                        color = Dsh.labelTertiary,
                        style = DshType.t13,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                    )
                    SessionListKind.Error -> Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            catalogError ?: L.loadWorkspaceListFailed,
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
                                .clickable(onClick = onRetry)
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                    SessionListKind.Content, SessionListKind.Empty -> {
                        filtered.forEach { ws ->
                            WorkspaceOptionRow(
                                title = ws.substringAfterLast('/'),
                                path = ws,
                                selected = ws == selectedPath,
                                onClick = {
                                    onDismiss()
                                    onPick(ws)
                                }
                            )
                        }
                        if (query.isNotBlank() && filtered.isEmpty()) {
                            Text(
                                L.noMatchingWorkspace.format(query),
                                color = Dsh.labelTertiary,
                                style = DshType.t13,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            AddWorkspaceRow(
                onCreate = { path ->
                    onDismiss()
                    onPick(path)
                }
            )
        }
    }
}

@Composable
internal fun SheetSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(Dsh.bgTrack)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(SearchOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = DshType.t14x20.copy(color = Dsh.labelPrimary),
            cursorBrush = SolidColor(Dsh.brand400),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = Dsh.labelTertiary, style = DshType.t14)
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = L.clearSearch
                    }
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    CloseOutline16,
                    contentDescription = null,
                    tint = Dsh.labelTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
internal fun WorkspaceOptionRow(
    title: String,
    path: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .background(if (pressed) Dsh.pressed else Color.Transparent)
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(DshRadius.md))
                .background(if (selected) Dsh.brand400.copy(alpha = 0.16f) else Dsh.bgCard),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                FolderOpenOutline16,
                contentDescription = null,
                tint = if (selected) Dsh.brand400 else Dsh.labelSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Dsh.labelPrimary,
                style = DshType.t15x20M,
                fontWeight = FontWeight(500),
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                abbreviateHomePath(path),
                color = Dsh.labelTertiary,
                style = DshType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(CheckOutline16, contentDescription = null, tint = Dsh.brand400, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun AddWorkspaceRow(onCreate: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(Dsh.bgInput)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(DshRadius.md))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Dsh.brand400.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(PlusOutline16, contentDescription = null, tint = Dsh.brand400, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(L.addWorkspace, color = Dsh.labelPrimary, style = DshType.labelLarge, fontWeight = FontWeight(500), lineHeight = 20.sp)
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) ChevronUpOutline14 else ChevronDownOutline14,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(14.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    textStyle = DshType.t13.copy(color = Dsh.labelPrimary),
                    cursorBrush = SolidColor(Dsh.brand400),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(DshRadius.md))
                        .background(Dsh.bgSubtle)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (path.isEmpty()) Text(L.enterWorkspacePath, color = Dsh.labelTertiary, style = DshType.t13)
                            inner()
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(DshRadius.md))
                        .background(if (path.isBlank()) Dsh.buttonElevated.copy(alpha = 0.5f) else Dsh.brand400)
                        .clickable(enabled = path.isNotBlank()) { onCreate(path.trim()) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(L.create, color = if (path.isBlank()) Dsh.labelTertiary else Dsh.onBrand, style = DshType.t13M, fontWeight = FontWeight(500))
                }
            }
        }
    }
}