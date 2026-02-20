package dev.minios.ocremote.ui.screens.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.ProviderIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerModelFilterScreen(
    onNavigateBack: () -> Unit,
    viewModel: ServerSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAmoled = MaterialTheme.colorScheme.background == Color.Black && MaterialTheme.colorScheme.surface == Color.Black
    var search by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf<String?>(null) }
    var defaultsCollapsed by rememberSaveable { mutableStateOf(true) }

    val defaultModelLabel = uiState.modelOptions.find { it.key == uiState.selectedModel }?.label
        ?: uiState.selectedModel
        ?: stringResource(R.string.server_settings_auto)
    val smallModelLabel = uiState.modelOptions.find { it.key == uiState.selectedSmallModel }?.label
        ?: uiState.selectedSmallModel
        ?: stringResource(R.string.server_settings_auto)
    val defaultAgentLabel = uiState.selectedDefaultAgent ?: stringResource(R.string.server_settings_auto)

    val normalized = search.trim().lowercase()
    val filteredGroups = uiState.groups.mapNotNull { group ->
        val models = group.models.filter {
            normalized.isEmpty() ||
                it.modelName.lowercase().contains(normalized) ||
                it.modelId.lowercase().contains(normalized)
        }
        if (models.isEmpty()) return@mapNotNull null
        group.copy(models = models)
    }

    picker?.let { kind ->
        val selected = when (kind) {
            "model" -> uiState.selectedModel
            "small" -> uiState.selectedSmallModel
            else -> uiState.selectedDefaultAgent
        }
        if (kind == "model" || kind == "small") {
            val allowed = uiState.modelOptions.map { it.key }.toSet()
            val groupedOptions = uiState.groups
                .mapNotNull { group ->
                    val models = group.models
                        .filter { it.visible && "${group.providerId}/${it.modelId}" in allowed }
                        .sortedBy { it.modelName.lowercase() }
                    if (models.isEmpty()) return@mapNotNull null
                    group to models
                }
                .sortedBy { it.first.providerName.lowercase() }

            BasicAlertDialog(onDismissRequest = { picker = null }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                    tonalElevation = if (isAmoled) 0.dp else 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        item(key = "auto") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected == null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        picker = null
                                        if (kind == "model") viewModel.setDefaultModel(null) else viewModel.setSmallModel(null)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.server_settings_auto),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (selected == null) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.padding(start = 8.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        for ((index, entry) in groupedOptions.withIndex()) {
                            val group = entry.first
                            val models = entry.second
                            val topPad = if (index == 0) 4.dp else 12.dp

                            item(key = "provider_header_${group.providerId}") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = topPad, bottom = 2.dp, start = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    ProviderIcon(
                                        providerId = group.providerId,
                                        size = 14.dp,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        text = group.providerName.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        letterSpacing = 1.sp
                                    )
                                }
                            }

                            items(models, key = { "model_${group.providerId}_${it.modelId}" }) { model ->
                                val modelKey = "${group.providerId}/${model.modelId}"
                                val isSelected = selected == modelKey
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            picker = null
                                            if (kind == "model") viewModel.setDefaultModel(modelKey) else viewModel.setSmallModel(modelKey)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = model.modelName.ifEmpty { model.modelId },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val options = uiState.agentOptions.map { it to it }
            val title = stringResource(R.string.server_settings_default_agent)
            BasicAlertDialog(onDismissRequest = { picker = null }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                    border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                    tonalElevation = if (isAmoled) 0.dp else 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(horizontal = 12.dp)
                        ) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected == null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            picker = null
                                            viewModel.setDefaultAgent(null)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.server_settings_auto),
                                        modifier = Modifier.weight(1f),
                                        color = if (selected == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (selected == null) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            items(options, key = { it.first }) { option ->
                                val isSelected = selected == option.first
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            picker = null
                                            viewModel.setDefaultAgent(option.first)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = option.second,
                                        modifier = Modifier.weight(1f),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { picker = null }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_settings_models)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { defaultsCollapsed = !defaultsCollapsed }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.server_settings_defaults), style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = "$defaultModelLabel · $smallModelLabel · $defaultAgentLabel",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                    Icon(
                        imageVector = if (defaultsCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = null
                    )
                }
            }

            if (!defaultsCollapsed) {
                Spacer(Modifier.height(6.dp))
                DefaultsRow(
                    title = stringResource(R.string.server_settings_default_model),
                    value = defaultModelLabel,
                    onClick = { picker = "model" },
                    isAmoled = isAmoled
                )
                Spacer(Modifier.height(4.dp))

                DefaultsRow(
                    title = stringResource(R.string.server_settings_small_model),
                    value = smallModelLabel,
                    onClick = { picker = "small" },
                    isAmoled = isAmoled
                )
                Spacer(Modifier.height(4.dp))

                DefaultsRow(
                    title = stringResource(R.string.server_settings_default_agent),
                    value = defaultAgentLabel,
                    onClick = { picker = "agent" },
                    isAmoled = isAmoled
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                placeholder = { Text(stringResource(R.string.server_settings_search_placeholder)) },
                singleLine = true,
                colors = if (isAmoled) {
                    androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Black,
                        unfocusedContainerColor = Color.Black,
                        disabledContainerColor = Color.Black,
                    )
                } else {
                    androidx.compose.material3.OutlinedTextFieldDefaults.colors()
                }
            )
            Spacer(Modifier.height(8.dp))

            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.loading))
                    }
                }

                uiState.error != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            Text(uiState.error ?: stringResource(R.string.server_settings_load_error))
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.loadProviders() }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }

                filteredGroups.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.server_settings_empty))
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredGroups, key = { it.providerId }) { group ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                                border = if (isAmoled) {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                                } else {
                                    null
                                }
                            ) {
                                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                    Text(
                                        text = group.providerName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                    group.models.forEach { model ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = model.modelName,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    text = model.modelId,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                )
                                            }
                                            Switch(
                                                checked = model.visible,
                                                onCheckedChange = { checked ->
                                                    viewModel.setModelVisible(group.providerId, model.modelId, checked)
                                                },
                                                colors = if (isAmoled) {
                                                    SwitchDefaults.colors(
                                                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                                                        checkedTrackColor = Color.Black,
                                                        checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                                        uncheckedTrackColor = Color.Black,
                                                        uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
                                                    )
                                                } else {
                                                    SwitchDefaults.colors()
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
        }
    }
}

@Composable
private fun DefaultsRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    isAmoled: Boolean
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium)
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
            }
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
    }
}
