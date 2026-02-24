package dev.minios.ocremote.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.minios.ocremote.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings Screen - global app preferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val currentLanguage by viewModel.appLanguage.collectAsState()
    val currentTheme by viewModel.appTheme.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()
    val chatFontSize by viewModel.chatFontSize.collectAsState()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()

    val initialMessageCount by viewModel.initialMessageCount.collectAsState()
    val codeWordWrap by viewModel.codeWordWrap.collectAsState()
    val confirmBeforeSend by viewModel.confirmBeforeSend.collectAsState()
    val amoledDark by viewModel.amoledDark.collectAsState()
    val compactMessages by viewModel.compactMessages.collectAsState()
    val collapseTools by viewModel.collapseTools.collectAsState()
    val hapticFeedback by viewModel.hapticFeedback.collectAsState()
    val reconnectMode by viewModel.reconnectMode.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val silentNotifications by viewModel.silentNotifications.collectAsState()
    val showShellButton by viewModel.showShellButton.collectAsState()
    val compressImageAttachments by viewModel.compressImageAttachments.collectAsState()
    val imageAttachmentMaxLongSide by viewModel.imageAttachmentMaxLongSide.collectAsState()
    val imageAttachmentWebpQuality by viewModel.imageAttachmentWebpQuality.collectAsState()
    val showLocalRuntime by viewModel.showLocalRuntime.collectAsState()
    val terminalFontSize by viewModel.terminalFontSize.collectAsState()

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showMessageCountDialog by remember { mutableStateOf(false) }
    var showReconnectModeDialog by remember { mutableStateOf(false) }
    var showTerminalFontSizeDialog by remember { mutableStateOf(false) }
    var showImageMaxSideDialog by remember { mutableStateOf(false) }
    var showImageQualityDialog by remember { mutableStateOf(false) }

    val isAmoledTheme = MaterialTheme.colorScheme.background == Color.Black &&
        MaterialTheme.colorScheme.surface == Color.Black
    val switchColors = if (isAmoledTheme) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ======== General ========
            SectionHeader(stringResource(R.string.settings_section_general))

            // Language
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                supportingContent = { Text(getLanguageDisplayName(currentLanguage)) },
                leadingContent = {
                    Icon(Icons.Default.Language, contentDescription = null)
                },
                modifier = Modifier.clickable { showLanguageDialog = true }
            )

            // Reconnect mode
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_reconnect_mode)) },
                supportingContent = { Text(getReconnectModeDisplayName(reconnectMode)) },
                leadingContent = {
                    Icon(Icons.Default.Sync, contentDescription = null)
                },
                modifier = Modifier.clickable { showReconnectModeDialog = true }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Appearance ========
            SectionHeader(stringResource(R.string.settings_section_appearance))

            // Theme
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_theme)) },
                supportingContent = { Text(getThemeDisplayName(currentTheme)) },
                leadingContent = {
                    Icon(Icons.Default.Palette, contentDescription = null)
                },
                modifier = Modifier.clickable { showThemeDialog = true }
            )

            // Dynamic colors (only on Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_dynamic_color)) },
                    supportingContent = { Text(stringResource(R.string.settings_dynamic_color_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.Palette, contentDescription = null)
                    },
                    trailingContent = {
                        Switch(
                            checked = dynamicColor,
                            onCheckedChange = { viewModel.setDynamicColor(it) },
                            colors = switchColors
                        )
                    },
                    modifier = Modifier.clickable { viewModel.setDynamicColor(!dynamicColor) }
                )
            }

            // AMOLED dark mode
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_amoled_dark)) },
                supportingContent = { Text(stringResource(R.string.settings_amoled_dark_desc)) },
                leadingContent = {
                    Icon(Icons.Default.DarkMode, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = amoledDark,
                        onCheckedChange = { viewModel.setAmoledDark(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setAmoledDark(!amoledDark) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Chat Display ========
            SectionHeader(stringResource(R.string.settings_section_chat_display))

            // Font size
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_font_size)) },
                supportingContent = { Text(getFontSizeDisplayName(chatFontSize)) },
                leadingContent = {
                    Icon(Icons.Default.FormatSize, contentDescription = null)
                },
                modifier = Modifier.clickable { showFontSizeDialog = true }
            )

            // Compact messages
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_compact_messages)) },
                supportingContent = { Text(stringResource(R.string.settings_compact_messages_desc)) },
                leadingContent = {
                    Icon(Icons.Default.ViewCompact, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = compactMessages,
                        onCheckedChange = { viewModel.setCompactMessages(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setCompactMessages(!compactMessages) }
            )

            // Code word wrap
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_code_word_wrap)) },
                supportingContent = { Text(stringResource(R.string.settings_code_word_wrap_desc)) },
                leadingContent = {
                    Icon(Icons.Default.WrapText, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = codeWordWrap,
                        onCheckedChange = { viewModel.setCodeWordWrap(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setCodeWordWrap(!codeWordWrap) }
            )

            // Auto-expand tool results
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_auto_expand_tools)) },
                supportingContent = { Text(stringResource(R.string.settings_auto_expand_tools_desc)) },
                leadingContent = {
                    Icon(Icons.Default.UnfoldMore, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = collapseTools,
                        onCheckedChange = { viewModel.setCollapseTools(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setCollapseTools(!collapseTools) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Chat Behavior ========
            SectionHeader(stringResource(R.string.settings_section_chat_behavior))

            // Initial message count
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_initial_messages)) },
                supportingContent = { Text("$initialMessageCount") },
                leadingContent = {
                    Icon(Icons.Default.Storage, contentDescription = null)
                },
                modifier = Modifier.clickable { showMessageCountDialog = true }
            )

            // Confirm before send
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_confirm_send)) },
                supportingContent = { Text(stringResource(R.string.settings_confirm_send_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Send, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = confirmBeforeSend,
                        onCheckedChange = { viewModel.setConfirmBeforeSend(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setConfirmBeforeSend(!confirmBeforeSend) }
            )

            // Haptic feedback
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_haptic_feedback)) },
                supportingContent = { Text(stringResource(R.string.settings_haptic_feedback_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Vibration, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = hapticFeedback,
                        onCheckedChange = { viewModel.setHapticFeedback(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setHapticFeedback(!hapticFeedback) }
            )

            // Keep screen on
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_keep_screen_on)) },
                supportingContent = { Text(stringResource(R.string.settings_keep_screen_on_desc)) },
                leadingContent = {
                    Icon(Icons.Default.ScreenLockPortrait, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = keepScreenOn,
                        onCheckedChange = { viewModel.setKeepScreenOn(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setKeepScreenOn(!keepScreenOn) }
            )

            // Show shell mode button
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_show_shell_button)) },
                supportingContent = { Text(stringResource(R.string.settings_show_shell_button_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Terminal, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = showShellButton,
                        onCheckedChange = { viewModel.setShowShellButton(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setShowShellButton(!showShellButton) }
            )

            // Optimize image attachments
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_compress_images)) },
                supportingContent = { Text(stringResource(R.string.settings_compress_images_desc)) },
                leadingContent = {
                    Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = compressImageAttachments,
                        onCheckedChange = { viewModel.setCompressImageAttachments(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable {
                    viewModel.setCompressImageAttachments(!compressImageAttachments)
                }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_compress_images_max_side)) },
                supportingContent = { Text(getImageMaxSideDisplayName(imageAttachmentMaxLongSide)) },
                leadingContent = {
                    Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = null)
                },
                modifier = Modifier.clickable(enabled = compressImageAttachments) { showImageMaxSideDialog = true }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_compress_images_quality)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_compress_images_quality_value, imageAttachmentWebpQuality))
                },
                leadingContent = {
                    Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = null)
                },
                modifier = Modifier.clickable(enabled = compressImageAttachments) { showImageQualityDialog = true }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_terminal_font_size)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_terminal_font_size_value, terminalFontSize.roundToInt()))
                },
                leadingContent = {
                    Icon(Icons.Default.Terminal, contentDescription = null)
                },
                modifier = Modifier.clickable { showTerminalFontSizeDialog = true }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Advanced ========
            SectionHeader(stringResource(R.string.settings_section_advanced))

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_local_runtime)) },
                supportingContent = { Text(stringResource(R.string.settings_local_runtime_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Code, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = showLocalRuntime,
                        onCheckedChange = { viewModel.setShowLocalRuntime(it) },
                        colors = switchColors,
                    )
                },
                modifier = Modifier.clickable { viewModel.setShowLocalRuntime(!showLocalRuntime) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ======== Notifications ========
            SectionHeader(stringResource(R.string.settings_section_notifications))

            // Notifications
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_notifications)) },
                supportingContent = { Text(stringResource(R.string.settings_notifications_desc)) },
                leadingContent = {
                    Icon(Icons.Default.Notifications, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = { viewModel.setNotificationsEnabled(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setNotificationsEnabled(!notificationsEnabled) }
            )

            // Silent notifications
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_silent_notifications)) },
                supportingContent = { Text(stringResource(R.string.settings_silent_notifications_desc)) },
                leadingContent = {
                    Icon(Icons.Default.NotificationsOff, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = silentNotifications,
                        onCheckedChange = { viewModel.setSilentNotifications(it) },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable { viewModel.setSilentNotifications(!silentNotifications) }
            )

        }

        if (showThemeDialog) {
            ThemePickerDialog(
                currentTheme = currentTheme,
                onThemeSelected = { theme ->
                    viewModel.setTheme(theme)
                    showThemeDialog = false
                },
                onDismiss = { showThemeDialog = false }
            )
        }

        if (showLanguageDialog) {
            LanguagePickerDialog(
                currentLanguage = currentLanguage,
                onLanguageSelected = { languageCode ->
                    viewModel.setLanguage(languageCode)
                    showLanguageDialog = false
                },
                onDismiss = { showLanguageDialog = false }
            )
        }

        if (showFontSizeDialog) {
            FontSizePickerDialog(
                currentSize = chatFontSize,
                onSizeSelected = { size ->
                    viewModel.setChatFontSize(size)
                    showFontSizeDialog = false
                },
                onDismiss = { showFontSizeDialog = false }
            )
        }

        if (showMessageCountDialog) {
            MessageCountPickerDialog(
                currentCount = initialMessageCount,
                onCountSelected = { count ->
                    viewModel.setInitialMessageCount(count)
                    showMessageCountDialog = false
                },
                onDismiss = { showMessageCountDialog = false }
            )
        }

        if (showReconnectModeDialog) {
            ReconnectModePickerDialog(
                currentMode = reconnectMode,
                onModeSelected = { mode ->
                    viewModel.setReconnectMode(mode)
                    showReconnectModeDialog = false
                },
                onDismiss = { showReconnectModeDialog = false }
            )
        }

        if (showTerminalFontSizeDialog) {
            TerminalFontSizeDialog(
                currentSize = terminalFontSize,
                onSizeSelected = { size ->
                    viewModel.setTerminalFontSize(size)
                    showTerminalFontSizeDialog = false
                },
                onDismiss = { showTerminalFontSizeDialog = false }
            )
        }

        if (showImageMaxSideDialog) {
            ImageCompressionMaxSideDialog(
                currentMaxSide = imageAttachmentMaxLongSide,
                onSelected = { px ->
                    viewModel.setImageAttachmentMaxLongSide(px)
                    showImageMaxSideDialog = false
                },
                onDismiss = { showImageMaxSideDialog = false }
            )
        }

        if (showImageQualityDialog) {
            ImageCompressionQualityDialog(
                currentQuality = imageAttachmentWebpQuality,
                onSelected = { quality ->
                    viewModel.setImageAttachmentWebpQuality(quality)
                    showImageQualityDialog = false
                },
                onDismiss = { showImageQualityDialog = false }
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun amoledDialogModifier(): Modifier {
    val isAmoledTheme = MaterialTheme.colorScheme.background == Color.Black &&
        MaterialTheme.colorScheme.surface == Color.Black
    return if (isAmoledTheme) {
        Modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f),
            shape = RoundedCornerShape(28.dp),
        )
    } else {
        Modifier
    }
}

@Composable
private fun amoledDialogContainerColor(): Color {
    val isAmoledTheme = MaterialTheme.colorScheme.background == Color.Black &&
        MaterialTheme.colorScheme.surface == Color.Black
    return if (isAmoledTheme) Color.Black else AlertDialogDefaults.containerColor
}

/**
 * Reusable single-selection picker dialog styled to match
 * the ModelPickerDialog visual language: selected item gets a
 * rounded background highlight and a check icon.
 *
 * @param title       Dialog title string.
 * @param options     List of key-label pairs to display.
 * @param selectedKey The currently selected key.
 * @param onSelect    Called with the key when an option is tapped.
 * @param onDismiss   Called when the dialog should close.
 * @param maxHeight   Maximum dialog body height (useful for long lists).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <K> SettingsPickerDialog(
    title: String,
    options: List<Pair<K, String>>,
    selectedKey: K,
    onSelect: (K) -> Unit,
    onDismiss: () -> Unit,
    maxHeight: Int = 480
) {
    val isAmoled = MaterialTheme.colorScheme.background == Color.Black &&
        MaterialTheme.colorScheme.surface == Color.Black

    val listState = rememberLazyListState()

    // Scroll to selected item on first composition
    val selectedIndex = remember(options, selectedKey) {
        options.indexOfFirst { it.first == selectedKey }.coerceAtLeast(0)
    }
    LaunchedEffect(selectedIndex) {
        listState.scrollToItem(selectedIndex)
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
            border = if (isAmoled) BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
            ) else null,
            tonalElevation = if (isAmoled) 0.dp else 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Title
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(
                        start = 24.dp,
                        end = 24.dp,
                        top = 20.dp,
                        bottom = 8.dp
                    )
                )

                // Items
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(
                        options,
                        key = { it.first.toString() }
                    ) { (key, label) ->
                        val isSelected = key == selectedKey
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        isSelected && isAmoled -> Color.Black
                                        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                        else -> Color.Transparent
                                    }
                                )
                                .then(
                                    if (isSelected && isAmoled) {
                                        Modifier.border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                                            shape = RoundedCornerShape(12.dp),
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { onSelect(key) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // Cancel button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemePickerDialog(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsPickerDialog(
        title = stringResource(R.string.dialog_select_theme),
        options = listOf(
            "system" to stringResource(R.string.settings_theme_system),
            "light" to stringResource(R.string.settings_theme_light),
            "dark" to stringResource(R.string.settings_theme_dark)
        ),
        selectedKey = currentTheme,
        onSelect = onThemeSelected,
        onDismiss = onDismiss
    )
}

@Composable
private fun LanguagePickerDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val systemDefault = stringResource(R.string.settings_language_system)

    SettingsPickerDialog(
        title = stringResource(R.string.dialog_select_language),
        options = listOf(
            "" to systemDefault,
            "en" to "English",
            "ar" to "العربية",
            "de" to "Deutsch",
            "es" to "Español",
            "fr" to "Français",
            "id" to "Bahasa Indonesia",
            "it" to "Italiano",
            "ja" to "日本語",
            "ko" to "한국어",
            "pl" to "Polski",
            "pt-BR" to "Português (Brasil)",
            "ru" to "Русский",
            "tr" to "Türkçe",
            "uk" to "Українська",
            "zh-CN" to "简体中文"
        ),
        selectedKey = currentLanguage,
        onSelect = onLanguageSelected,
        onDismiss = onDismiss,
        maxHeight = 520
    )
}

@Composable
private fun FontSizePickerDialog(
    currentSize: String,
    onSizeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsPickerDialog(
        title = stringResource(R.string.settings_font_size),
        options = listOf(
            "small" to stringResource(R.string.settings_font_size_small),
            "medium" to stringResource(R.string.settings_font_size_medium),
            "large" to stringResource(R.string.settings_font_size_large)
        ),
        selectedKey = currentSize,
        onSelect = onSizeSelected,
        onDismiss = onDismiss
    )
}

@Composable
private fun MessageCountPickerDialog(
    currentCount: Int,
    onCountSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsPickerDialog(
        title = stringResource(R.string.settings_initial_messages),
        options = listOf(25, 50, 100, 200).map { it to "$it" },
        selectedKey = currentCount,
        onSelect = onCountSelected,
        onDismiss = onDismiss
    )
}

@Composable
private fun ReconnectModePickerDialog(
    currentMode: String,
    onModeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsPickerDialog(
        title = stringResource(R.string.dialog_select_reconnect_mode),
        options = listOf(
            "aggressive" to stringResource(R.string.settings_reconnect_aggressive),
            "normal" to stringResource(R.string.settings_reconnect_normal),
            "conservative" to stringResource(R.string.settings_reconnect_conservative)
        ),
        selectedKey = currentMode,
        onSelect = onModeSelected,
        onDismiss = onDismiss
    )
}

@Composable
private fun TerminalFontSizeDialog(
    currentSize: Float,
    onSizeSelected: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(currentSize) { mutableFloatStateOf(currentSize.coerceIn(6f, 20f)) }

    AlertDialog(
        modifier = amoledDialogModifier(),
        onDismissRequest = onDismiss,
        containerColor = amoledDialogContainerColor(),
        title = { Text(stringResource(R.string.settings_terminal_font_size)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_terminal_font_size_value, selected.roundToInt()),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Slider(
                    value = selected,
                    onValueChange = { selected = it },
                    valueRange = 6f..20f,
                    steps = 13
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSizeSelected(selected.roundToInt().toFloat()) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ImageCompressionMaxSideDialog(
    currentMaxSide: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(640, 720, 960, 1080, 1440, 1920, 2560)
    SettingsPickerDialog(
        title = stringResource(R.string.settings_compress_images_max_side),
        options = options.map { it to getImageMaxSideDisplayName(it) },
        selectedKey = currentMaxSide,
        onSelect = onSelected,
        onDismiss = onDismiss
    )
}

@Composable
private fun ImageCompressionQualityDialog(
    currentQuality: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(40, 50, 60, 70, 80)
    SettingsPickerDialog(
        title = stringResource(R.string.settings_compress_images_quality),
        options = options.map {
            it to stringResource(R.string.settings_compress_images_quality_value, it)
        },
        selectedKey = currentQuality,
        onSelect = onSelected,
        onDismiss = onDismiss
    )
}

@Composable
private fun getThemeDisplayName(theme: String): String {
    return when (theme) {
        "system" -> stringResource(R.string.settings_theme_system)
        "light" -> stringResource(R.string.settings_theme_light)
        "dark" -> stringResource(R.string.settings_theme_dark)
        else -> theme
    }
}

@Composable
private fun getFontSizeDisplayName(size: String): String {
    return when (size) {
        "small" -> stringResource(R.string.settings_font_size_small)
        "medium" -> stringResource(R.string.settings_font_size_medium)
        "large" -> stringResource(R.string.settings_font_size_large)
        else -> size
    }
}

@Composable
private fun getLanguageDisplayName(code: String): String {
    val systemDefault = stringResource(R.string.settings_language_system)
    
    if (code.isEmpty()) return systemDefault
    
    // Parse the language tag and get native display name
    val locale = if (code.contains("-")) {
        val parts = code.split("-")
        if (parts.size >= 2) {
            Locale(parts[0], parts[1].uppercase())
        } else {
            Locale(parts[0])
        }
    } else {
        Locale(code)
    }
    
    return locale.getDisplayName(locale).replaceFirstChar { 
        if (it.isLowerCase()) it.titlecase(locale) else it.toString() 
    }
}

@Composable
private fun getReconnectModeDisplayName(mode: String): String {
    return when (mode) {
        "aggressive" -> stringResource(R.string.settings_reconnect_aggressive)
        "normal" -> stringResource(R.string.settings_reconnect_normal)
        "conservative" -> stringResource(R.string.settings_reconnect_conservative)
        else -> mode
    }
}

@Composable
private fun getImageMaxSideDisplayName(px: Int): String {
    return stringResource(R.string.settings_compress_images_max_side_value, px)
}
