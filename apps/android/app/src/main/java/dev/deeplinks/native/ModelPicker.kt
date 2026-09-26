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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.catalogKind
import dev.deeplinks.native.util.compactTokens
import java.util.Locale


private enum class ModelPickerPage { MENU, MODELS, EFFORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    catalog: MobileModelCatalog?,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit,
    onSelect: (provider: String, model: String, effort: String?) -> Unit,
) {
    var page by remember { mutableStateOf(ModelPickerPage.MENU) }
    var query by remember { mutableStateOf("") }
    val currentOption = remember(catalog) {
        catalog?.groups?.asSequence()?.flatMap { g -> g.models.asSequence().map { g to it } }
            ?.firstOrNull { (g, m) ->
                m.id == catalog.currentModel &&
                    (g.provider == catalog.currentProvider || g.displayName == catalog.currentProvider)
            }
    }
    val currentName = currentOption?.second?.name ?: catalog?.currentModel
    val currentEffort = catalog?.currentReasoningEffort
        ?: currentOption?.second?.defaultEffort
    val currentEfforts = currentOption?.second?.reasoningEfforts.orEmpty()
    val filteredGroups = remember(catalog, query) {
        val groups = catalog?.groups.orEmpty()
        if (query.isBlank()) groups
        else groups.mapNotNull { group ->
            val models = group.models.filter {
                it.id.contains(query, ignoreCase = true) ||
                    (it.name?.contains(query, ignoreCase = true) == true) ||
                    group.displayName.contains(query, ignoreCase = true)
            }
            if (models.isEmpty()) null else group.copy(models = models)
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
                .padding(bottom = 20.dp)
        ) {
            DshSheetGrabber()
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (page != ModelPickerPage.MENU) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .semantics {
                                role = Role.Button
                                contentDescription = L.back
                            }
                            .clickable { page = ModelPickerPage.MENU },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            ChevronLeftOutline14,
                            contentDescription = null,
                            tint = Dsh.labelSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when (page) {
                        ModelPickerPage.MENU -> L.modelAndEffort
                        ModelPickerPage.MODELS -> L.model
                        ModelPickerPage.EFFORT -> L.reasoningEffort
                    },
                    color = Dsh.labelPrimary,
                    style = DshType.headline,
                    fontWeight = FontWeight(600),
                    lineHeight = 24.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (currentName != null) {
                    listOfNotNull(currentName, currentEffort?.let { formatEffortLabel(it) }).joinToString(" ")
                } else L.selectSessionModel,
                color = Dsh.labelTertiary,
                style = DshType.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!error.isNullOrBlank() && catalog != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    error,
                    color = Dsh.error,
                    style = DshType.titleSmall,
                )
            }
            Spacer(Modifier.height(14.dp))

            when (page) {
                ModelPickerPage.MENU -> {
                    ModelMenuRow(
                        title = L.model,
                        value = currentName ?: L.noneSelected,
                        sub = currentOption?.second?.contextWindow?.let { compactTokens(it) },
                        icon = Sparkle16,
                        accent = Dsh.brand400,
                        onClick = { page = ModelPickerPage.MODELS }
                    )
                    Spacer(Modifier.height(8.dp))
                    ModelMenuRow(
                        title = L.reasoningEffort,
                        value = currentEffort?.let { formatEffortLabel(it) } ?: if (currentEfforts.isEmpty()) "—" else L.defaultLabel,
                        sub = currentName?.let { L.reasoningEffortForModel.format(it) },
                        icon = ThinkOutline16,
                        accent = Dsh.systemAccent,
                        enabled = currentEfforts.isNotEmpty(),
                        onClick = { page = ModelPickerPage.EFFORT }
                    )
                }
                ModelPickerPage.MODELS -> {
                    SheetSearchField(value = query, onValueChange = { query = it }, placeholder = L.searchModelProvider)
                    Spacer(Modifier.height(12.dp))
                    when (catalogKind(
                        hasItems = catalog?.groups?.isNotEmpty() == true,
                        initialLoad = loading && catalog == null,
                        hasError = error != null && catalog == null,
                    )) {
                        SessionListKind.Loading -> Text(L.loadingModelList, color = Dsh.labelTertiary, style = DshType.t13, modifier = Modifier.padding(vertical = 16.dp))
                        SessionListKind.Error -> ChatHistoryError(
                            title = L.loadModelListFailed,
                            message = error ?: L.loadModelListFailed,
                            onRetry = onRetry,
                        )
                        SessionListKind.Empty -> Text(L.noAvailableModels, color = Dsh.labelTertiary, style = DshType.t13, modifier = Modifier.padding(vertical = 16.dp))
                        SessionListKind.Content -> if (filteredGroups.isEmpty()) {
                            Text(L.noMatchingModels.format(query), color = Dsh.labelTertiary, style = DshType.t13, modifier = Modifier.padding(vertical = 16.dp))
                        } else Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 440.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            filteredGroups.forEach { group ->
                                Row(
                                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val accent = providerAccent(group.displayName)
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(RoundedCornerShape(DshRadius.sm))
                                            .background(accent.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            group.displayName.take(1).uppercase(),
                                            color = accent,
                                            style = DshType.t11x12SB,
                                            fontWeight = FontWeight(600),
                                            lineHeight = 12.sp
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(group.displayName, color = Dsh.labelSecondary, style = DshType.t13SB)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    group.models.forEach { model ->
                                        val selected = model.id == catalog?.currentModel &&
                                            (group.provider == catalog.currentProvider || group.displayName == catalog.currentProvider)
                                        ModelOptionRow(
                                            name = model.name ?: model.id,
                                            contextWindow = model.contextWindow,
                                            reasoningEfforts = emptyList(),
                                            selected = selected,
                                            onClick = {
                                                val effort = if (selected) currentEffort else model.defaultEffort
                                                onSelect(group.provider, model.id, effort)
                                                page = ModelPickerPage.MENU
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                ModelPickerPage.EFFORT -> {
                    if (currentEfforts.isEmpty()) {
                        Text(L.noAdjustableReasoningEffort, color = Dsh.labelTertiary, style = DshType.t13, modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            currentEfforts.forEach { effort ->
                                val selected = effort.equals(currentEffort, ignoreCase = true)
                                ModelOptionRow(
                                    name = formatEffortLabel(effort),
                                    contextWindow = null,
                                    reasoningEfforts = emptyList(),
                                    selected = selected,
                                    onClick = {
                                        val pair = currentOption
                                        if (pair != null) {
                                            onSelect(pair.first.provider, pair.second.id, effort)
                                            page = ModelPickerPage.MENU
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelMenuRow(
    title: String,
    value: String,
    sub: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    accent: Color = Dsh.brand400,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(if (pressed && enabled) Dsh.pressed else Dsh.bgTrack)
            .border(1.dp, Dsh.borderSubtle, RoundedCornerShape(DshRadius.lg))
            .clickable(interactionSource = interaction, indication = dshRipple(), enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(DshRadius.md))
                    .background(accent.copy(alpha = if (enabled) 0.14f else 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) accent else Dsh.labelTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) Dsh.labelSecondary else Dsh.labelTertiary,
                style = DshType.t12x17,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                color = if (enabled) Dsh.labelPrimary else Dsh.labelTertiary,
                style = DshType.t15x20M,
                fontWeight = FontWeight(500),
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    sub,
                    color = Dsh.labelTertiary,
                    style = DshType.microRelaxed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            ChevronRightOutline14,
            contentDescription = null,
            tint = if (enabled) Dsh.labelTertiary else Dsh.labelTertiary.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
    }
}

internal fun formatEffortLabel(effort: String): String =
    effort.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

internal fun friendlySelectModelError(raw: String?): String {
    val text = raw?.trim().orEmpty().ifBlank { return L.unknownError }
    val lower = text.lowercase()
    return when {
        "no adapter registered" in lower -> L.noAdapterRegistered
        "unsupported_reasoning" in lower || "does not support reasoning" in lower -> L.unsupportedReasoningEffort
        "model-unavailable" in lower -> L.modelUnavailable
        "调用" in text && "api" in lower -> L.modelApiFailed
        else -> text
    }
}

@Composable
private fun providerAccent(provider: String): Color {
    // 选色策略：优先用主题已有语义色（brand400、success、warn），其余
    // 用 hash 从 DSH 品牌扩展色中选取，与 systemAccent/toolsAccent
    // 保持同一片调色板语义。
    val palettes = listOf(
        Dsh.brand400,
        Dsh.systemAccent,
        Dsh.toolsAccent,
        Dsh.success,
        Dsh.warn,
        Dsh.error,
    )
    val idx = (provider.hashCode() and 0x7FFFFFFF) % palettes.size
    return palettes[idx]
}

@Composable
private fun ModelOptionRow(
    name: String,
    contextWindow: Long?,
    reasoningEfforts: List<String>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(
                when {
                    selected -> Dsh.brand400.copy(alpha = 0.08f)
                    pressed -> Dsh.pressed
                    else -> Dsh.bgTrack
                }
            )
            .border(
                1.dp,
                if (selected) Dsh.brand400.copy(alpha = 0.28f) else Dsh.borderSubtle,
                RoundedCornerShape(DshRadius.lg)
            )
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = Dsh.labelPrimary,
                style = DshType.t15x20,
                fontWeight = if (selected) FontWeight(500) else FontWeight(400),
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = buildList {
                if (contextWindow != null) add(compactTokens(contextWindow))
                if (reasoningEfforts.isNotEmpty()) {
                    add(reasoningEfforts.take(4).joinToString(" · ") { formatEffortLabel(it) })
                }
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    meta.joinToString("  ·  "),
                    color = Dsh.labelTertiary,
                    style = DshType.microRelaxed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Dsh.brand400.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CheckOutline16, contentDescription = null, tint = Dsh.brand400, modifier = Modifier.size(14.dp))
            }
        }
    }
}