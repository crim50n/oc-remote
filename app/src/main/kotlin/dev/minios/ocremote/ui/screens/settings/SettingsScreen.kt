package dev.minios.ocremote.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.LocalServerManager
import dev.minios.ocremote.domain.model.SessionCategory
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.ui.components.AppHaptics
import dev.minios.ocremote.ui.components.AppHapticConfig
import dev.minios.ocremote.ui.components.AppDialogActions
import dev.minios.ocremote.ui.components.AppPrimaryButton
import dev.minios.ocremote.ui.components.AppSecondaryButton
import dev.minios.ocremote.ui.components.isAmoledTheme
import dev.minios.ocremote.ui.components.AppPickerItemShape
import dev.minios.ocremote.ui.components.appPopupBorder
import dev.minios.ocremote.ui.components.appPopupContainerColor
import dev.minios.ocremote.ui.components.appSelectedItemColor
import dev.minios.ocremote.ui.components.SessionCategoryColorKeys
import dev.minios.ocremote.ui.components.SessionCategoryIconKeys
import dev.minios.ocremote.ui.components.sessionCategoryColor
import dev.minios.ocremote.ui.components.sessionCategoryIcon
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings Screen - global app preferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToSync: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val currentLanguage by viewModel.appLanguage.collectAsState()
    val currentTheme by viewModel.appTheme.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()
    val chatFontSize by viewModel.chatFontSize.collectAsState()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()

    val initialMessageCount by viewModel.initialMessageCount.collectAsState()
    val messageHistoryResponseLimitMb by viewModel.messageHistoryResponseLimitMb.collectAsState()
    val recentDirectoryCount by viewModel.recentDirectoryCount.collectAsState()
    val codeWordWrap by viewModel.codeWordWrap.collectAsState()
    val confirmBeforeSend by viewModel.confirmBeforeSend.collectAsState()
    val amoledDark by viewModel.amoledDark.collectAsState()
    val compactMessages by viewModel.compactMessages.collectAsState()
    val collapseTools by viewModel.collapseTools.collectAsState()
    val expandReasoning by viewModel.expandReasoning.collectAsState()
    val showTurnDividers by viewModel.showTurnDividers.collectAsState()
    val hapticFeedback by viewModel.hapticFeedback.collectAsState()
    val hapticDurationMillis by viewModel.hapticDurationMillis.collectAsState()
    val hapticAmplitude by viewModel.hapticAmplitude.collectAsState()
    val reconnectMode by viewModel.reconnectMode.collectAsState()
    val backgroundWakeLock by viewModel.backgroundWakeLock.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val silentNotifications by viewModel.silentNotifications.collectAsState()
    val compressImageAttachments by viewModel.compressImageAttachments.collectAsState()
    val imageAttachmentMaxLongSide by viewModel.imageAttachmentMaxLongSide.collectAsState()
    val imageAttachmentWebpQuality by viewModel.imageAttachmentWebpQuality.collectAsState()
    val showLocalRuntime by viewModel.showLocalRuntime.collectAsState()
    val terminalFontSize by viewModel.terminalFontSize.collectAsState()
    val showTerminalPanelHint by viewModel.showTerminalPanelHint.collectAsState()

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showMessageCountDialog by remember { mutableStateOf(false) }
    var showMessageHistoryResponseLimitDialog by remember { mutableStateOf(false) }
    var showRecentDirectoryCountDialog by remember { mutableStateOf(false) }
    var showReconnectModeDialog by remember { mutableStateOf(false) }
    var showHapticPatternDialog by remember { mutableStateOf(false) }
    var showTerminalFontSizeDialog by remember { mutableStateOf(false) }
    var showImageMaxSideDialog by remember { mutableStateOf(false) }
    var showImageQualityDialog by remember { mutableStateOf(false) }

    val isAmoled = isAmoledTheme()
    val settingsView = LocalView.current
    val switchColors = if (isAmoled) {
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
        containerColor = MaterialTheme.colorScheme.surface,
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
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

            ListItem(
                headlineContent = { Text(stringResource(R.string.sync_title)) },
                supportingContent = { Text(stringResource(R.string.sync_settings_desc_v2)) },
                leadingContent = { Icon(Icons.Default.CloudSync, contentDescription = null) },
                modifier = Modifier.clickable { onNavigateToSync() },
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

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_recent_directories)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_recent_directories_desc, recentDirectoryCount))
                },
                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                modifier = Modifier.clickable { showRecentDirectoryCountDialog = true },
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_background_wake_lock)) },
                supportingContent = { Text(stringResource(R.string.settings_background_wake_lock_desc)) },
                leadingContent = {
                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
                },
                trailingContent = {
                    Switch(
                        checked = backgroundWakeLock,
                        onCheckedChange = { viewModel.setBackgroundWakeLock(it) },
                        colors = switchColors,
                    )
                },
                modifier = Modifier.clickable {
                    viewModel.setBackgroundWakeLock(!backgroundWakeLock)
                },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

            // ======== Notifications ========
            SectionHeader(stringResource(R.string.settings_section_notifications))

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
                        enabled = notificationsEnabled,
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable(enabled = notificationsEnabled) {
                    viewModel.setSilentNotifications(!silentNotifications)
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

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
                        Icon(Icons.Default.ColorLens, contentDescription = null)
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

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

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_terminal_panel_hint)) },
                supportingContent = { Text(stringResource(R.string.settings_terminal_panel_hint_desc)) },
                leadingContent = { Icon(Icons.Default.Terminal, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = showTerminalPanelHint,
                        onCheckedChange = viewModel::setShowTerminalPanelHint,
                        colors = switchColors,
                    )
                },
                modifier = Modifier.clickable {
                    viewModel.setShowTerminalPanelHint(!showTerminalPanelHint)
                },
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
                    Icon(Icons.AutoMirrored.Filled.WrapText, contentDescription = null)
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

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_expand_reasoning)) },
                supportingContent = { Text(stringResource(R.string.settings_expand_reasoning_desc)) },
                leadingContent = { Icon(Icons.Default.UnfoldMore, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = expandReasoning,
                        onCheckedChange = { viewModel.setExpandReasoning(it) },
                        colors = switchColors,
                    )
                },
                modifier = Modifier.clickable { viewModel.setExpandReasoning(!expandReasoning) },
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_turn_dividers)) },
                supportingContent = { Text(stringResource(R.string.settings_turn_dividers_desc)) },
                leadingContent = { Icon(Icons.Default.HorizontalRule, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = showTurnDividers,
                        onCheckedChange = { viewModel.setShowTurnDividers(it) },
                        colors = switchColors,
                    )
                },
                modifier = Modifier.clickable { viewModel.setShowTurnDividers(!showTurnDividers) },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

            // ======== Chat Behavior ========
            SectionHeader(stringResource(R.string.settings_section_chat_behavior))

            // Initial message count
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_initial_messages)) },
                supportingContent = { Text("$initialMessageCount") },
                leadingContent = {
                    Icon(Icons.Default.History, contentDescription = null)
                },
                modifier = Modifier.clickable { showMessageCountDialog = true }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_history_response_limit)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_history_response_limit_value, messageHistoryResponseLimitMb))
                },
                leadingContent = { Icon(Icons.Default.History, contentDescription = null) },
                modifier = Modifier.clickable { showMessageHistoryResponseLimitDialog = true },
            )

            // Confirm before send
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_confirm_send)) },
                supportingContent = { Text(stringResource(R.string.settings_confirm_send_desc)) },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
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
                        onCheckedChange = {
                            viewModel.setHapticFeedback(it)
                            AppHaptics.perform(
                                settingsView,
                                AppHapticConfig(it, hapticDurationMillis, hapticAmplitude),
                            )
                        },
                        colors = switchColors
                    )
                },
                modifier = Modifier.clickable {
                    val enabled = !hapticFeedback
                    viewModel.setHapticFeedback(enabled)
                    AppHaptics.perform(
                        settingsView,
                        AppHapticConfig(enabled, hapticDurationMillis, hapticAmplitude),
                    )
                }
            )

            if (hapticFeedback) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_haptic_pattern)) },
                    supportingContent = {
                        Text(stringResource(R.string.settings_haptic_pattern_value, hapticDurationMillis, hapticAmplitude))
                    },
                    leadingContent = { Icon(Icons.Default.Vibration, contentDescription = null) },
                    modifier = Modifier.clickable { showHapticPatternDialog = true },
                )
            }

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

            if (compressImageAttachments) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_compress_images_max_side)) },
                    supportingContent = { Text(getImageMaxSideDisplayName(imageAttachmentMaxLongSide)) },
                    leadingContent = {
                        Spacer(modifier = Modifier.width(24.dp))
                    },
                    modifier = Modifier.clickable { showImageMaxSideDialog = true }
                )

                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_compress_images_quality)) },
                    supportingContent = {
                        Text(stringResource(R.string.settings_compress_images_quality_value, imageAttachmentWebpQuality))
                    },
                    leadingContent = {
                        Spacer(modifier = Modifier.width(24.dp))
                    },
                    modifier = Modifier.clickable { showImageQualityDialog = true }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

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

            ListItem(
                headlineContent = { Text(stringResource(R.string.diagnostics_title)) },
                supportingContent = { Text(stringResource(R.string.diagnostics_settings_desc)) },
                leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onNavigateToDiagnostics),
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

        if (showMessageHistoryResponseLimitDialog) {
            MessageHistoryResponseLimitPickerDialog(
                currentLimitMb = messageHistoryResponseLimitMb,
                onLimitSelected = { limitMb ->
                    viewModel.setMessageHistoryResponseLimitMb(limitMb)
                    showMessageHistoryResponseLimitDialog = false
                },
                onDismiss = { showMessageHistoryResponseLimitDialog = false },
            )
        }

        if (showRecentDirectoryCountDialog) {
            RecentDirectoryCountPickerDialog(
                currentCount = recentDirectoryCount,
                onCountSelected = { count ->
                    viewModel.setRecentDirectoryCount(count)
                    showRecentDirectoryCountDialog = false
                },
                onDismiss = { showRecentDirectoryCountDialog = false },
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

        if (showHapticPatternDialog) {
            HapticPatternDialog(
                currentDurationMillis = hapticDurationMillis,
                currentAmplitude = hapticAmplitude,
                onSave = { durationMillis, amplitude ->
                    viewModel.setHapticPattern(durationMillis, amplitude)
                    showHapticPatternDialog = false
                },
                onDismiss = { showHapticPatternDialog = false },
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
internal fun SessionCategoriesDialog(
    categories: List<SessionCategory>,
    onSave: (String?, String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(SessionCategoryColorKeys.first()) }
    var icon by remember { mutableStateOf(SessionCategoryIconKeys.first()) }

    fun edit(category: SessionCategory?) {
        editingId = category?.id
        name = category?.name.orEmpty()
        color = category?.color ?: SessionCategoryColorKeys.first()
        icon = category?.icon ?: SessionCategoryIconKeys.first()
        editing = true
    }

    AppDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(
                    if (editing) R.string.settings_category_edit else R.string.settings_session_categories
                ),
                style = MaterialTheme.typography.titleMedium,
            )

            if (editing) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_category_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(stringResource(R.string.settings_category_color), style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SessionCategoryColorKeys.forEach { key ->
                        val selected = key == color
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(sessionCategoryColor(key))
                                .then(
                                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier
                                )
                                .clickable { color = key },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                Text(stringResource(R.string.settings_category_icon), style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SessionCategoryIconKeys.forEach { key ->
                        val selected = key == icon
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                selected && !isAmoledTheme() -> sessionCategoryColor(color).copy(alpha = 0.18f)
                                else -> Color.Transparent
                            },
                            border = if (selected) BorderStroke(1.dp, sessionCategoryColor(color)) else null,
                            modifier = Modifier.clickable { icon = key },
                        ) {
                            Icon(
                                imageVector = sessionCategoryIcon(key),
                                contentDescription = null,
                                tint = if (selected) sessionCategoryColor(color) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp).size(22.dp),
                            )
                        }
                    }
                }

                AppDialogActions(
                    dismissText = stringResource(R.string.cancel),
                    confirmText = stringResource(R.string.settings_category_save),
                    onDismiss = { editing = false },
                    onConfirm = {
                        onSave(editingId, name, color, icon)
                        editing = false
                    },
                    confirmEnabled = name.isNotBlank(),
                )
            } else {
                if (categories.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_categories_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        categories.forEach { category ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { edit(category) }
                                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = sessionCategoryIcon(category.icon),
                                    contentDescription = null,
                                    tint = sessionCategoryColor(category.color),
                                )
                                Text(
                                    text = category.name,
                                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                IconButton(onClick = { onDelete(category.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.settings_category_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                AppDialogActions(
                    dismissText = stringResource(R.string.close),
                    confirmText = stringResource(R.string.settings_category_add),
                    onDismiss = onDismiss,
                    onConfirm = { edit(null) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalServerLaunchOptionsDialog(
    enabled: Boolean,
    proxyUrl: String,
    noProxyList: String,
    allowLanAccess: Boolean,
    serverUsername: String,
    serverPassword: String,
    runInBackground: Boolean,
    autoStart: Boolean,
    startupTimeoutSec: Int,
    onDismiss: () -> Unit,
    onSave: (
        enabled: Boolean,
        proxyUrl: String,
        noProxyList: String,
        allowLanAccess: Boolean,
        serverUsername: String,
        serverPassword: String,
        runInBackground: Boolean,
        autoStart: Boolean,
        startupTimeoutSec: Int,
    ) -> Unit,
) {
    val isAmoled = isAmoledTheme()
    var localEnabled by remember(enabled) { mutableStateOf(enabled) }
    var localProxyUrl by remember(proxyUrl) { mutableStateOf(proxyUrl) }
    var localNoProxyList by remember(noProxyList) { mutableStateOf(noProxyList) }
    var localAllowLanAccess by remember(allowLanAccess) { mutableStateOf(allowLanAccess) }
    var localServerUsername by remember(serverUsername) { mutableStateOf(serverUsername) }
    var localServerPassword by remember(serverPassword) { mutableStateOf(serverPassword) }
    var localRunInBackground by remember(runInBackground) { mutableStateOf(runInBackground) }
    var localAutoStart by remember(autoStart) { mutableStateOf(autoStart) }
    var localStartupTimeoutSec by remember(startupTimeoutSec) { mutableIntStateOf(startupTimeoutSec) }
    var maskProxyUrl by remember { mutableStateOf(true) }
    var maskServerPassword by remember { mutableStateOf(true) }
    var timeoutExpanded by remember { mutableStateOf(false) }
    val timeoutOptions = listOf(15, 30, 45, 60, 90, 120)
    val trimmedProxyUrl = localProxyUrl.trim()
    val trimmedNoProxy = localNoProxyList.trim()
    val trimmedServerPassword = localServerPassword.trim()
    val canSave = !localEnabled || trimmedProxyUrl.isNotBlank()
    val dialogListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    val bodyMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.58f
    val bodyScrollState = rememberScrollState()

    val switchColors = if (isAmoled) {
        SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedTrackColor = Color.Black,
            checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = Color.Black,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
        )
    } else {
        SwitchDefaults.colors()
    }

    AppDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.home_local_launch_options),
                style = MaterialTheme.typography.titleMedium,
            )

            Column(
                modifier = Modifier
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(bodyScrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.home_local_network_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_local_allow_lan_access)) },
                    supportingContent = { Text(stringResource(R.string.home_local_allow_lan_access_desc)) },
                    trailingContent = {
                        Switch(
                            checked = localAllowLanAccess,
                            onCheckedChange = { localAllowLanAccess = it },
                            colors = switchColors,
                        )
                    },
                    colors = dialogListItemColors,
                )

                Text(stringResource(R.string.home_local_security_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = localServerUsername,
                    onValueChange = { localServerUsername = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.home_local_server_username_label)) },
                    placeholder = { Text(stringResource(R.string.home_local_server_username_placeholder)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                )

                OutlinedTextField(
                    value = localServerPassword,
                    onValueChange = { localServerPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.home_local_server_password_label)) },
                    placeholder = { Text(stringResource(R.string.home_local_server_password_placeholder)) },
                    trailingIcon = {
                        IconButton(onClick = { maskServerPassword = !maskServerPassword }) {
                            Icon(
                                imageVector = if (maskServerPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                            )
                        }
                    },
                    visualTransformation = if (maskServerPassword) FullStringMaskTransformation else VisualTransformation.None,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                )

                if (localAllowLanAccess && trimmedServerPassword.isBlank()) {
                    Text(
                        text = stringResource(R.string.home_local_lan_password_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(stringResource(R.string.home_local_proxy_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_local_proxy_enable)) },
                    supportingContent = { Text(stringResource(R.string.home_local_proxy_url_label)) },
                    trailingContent = {
                        Switch(
                            checked = localEnabled,
                            onCheckedChange = { localEnabled = it },
                            colors = switchColors,
                        )
                    },
                    colors = dialogListItemColors,
                )

                if (localEnabled) {
                    OutlinedTextField(
                        value = localProxyUrl,
                        onValueChange = { localProxyUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.home_local_proxy_url_label)) },
                        placeholder = { Text("http://127.0.0.1:8080") },
                        trailingIcon = {
                            IconButton(onClick = { maskProxyUrl = !maskProxyUrl }) {
                                Icon(
                                    imageVector = if (maskProxyUrl) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                )
                            }
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                        isError = trimmedProxyUrl.isBlank(),
                        visualTransformation = if (maskProxyUrl) FullStringMaskTransformation else VisualTransformation.None,
                    )

                    OutlinedTextField(
                        value = localNoProxyList,
                        onValueChange = { localNoProxyList = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        label = { Text(stringResource(R.string.home_local_proxy_no_proxy_label)) },
                        placeholder = { Text(LocalServerManager.DEFAULT_NO_PROXY_LIST) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                }

                if (localEnabled) {
                    Text(
                        text = stringResource(R.string.home_local_proxy_no_proxy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(stringResource(R.string.home_local_autostart_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_local_run_background_label)) },
                    supportingContent = { Text(stringResource(R.string.home_local_run_background_desc)) },
                    trailingContent = {
                        Switch(
                            checked = localRunInBackground,
                            onCheckedChange = {
                                localRunInBackground = it
                                if (!it) {
                                    localAutoStart = false
                                }
                            },
                            colors = switchColors,
                        )
                    },
                    colors = dialogListItemColors,
                )

                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_local_auto_start_label)) },
                    supportingContent = {
                        Text(
                            if (localRunInBackground) {
                                stringResource(R.string.home_local_auto_start_desc)
                            } else {
                                stringResource(R.string.home_local_auto_start_requires_background)
                            }
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = localAutoStart,
                            onCheckedChange = { localAutoStart = it },
                            enabled = localRunInBackground,
                            colors = switchColors,
                        )
                    },
                    colors = dialogListItemColors,
                )

                ExposedDropdownMenuBox(
                    expanded = timeoutExpanded,
                    onExpandedChange = { timeoutExpanded = !timeoutExpanded },
                ) {
                    OutlinedTextField(
                        value = stringResource(R.string.home_local_startup_timeout_value, localStartupTimeoutSec),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        label = { Text(stringResource(R.string.home_local_startup_timeout_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = timeoutExpanded) },
                    )
                    ExposedDropdownMenu(
                        expanded = timeoutExpanded,
                        onDismissRequest = { timeoutExpanded = false },
                        modifier = Modifier.appPopupBorder(),
                        containerColor = appPopupContainerColor(),
                    ) {
                        timeoutOptions.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_local_startup_timeout_value, value)) },
                                onClick = {
                                    localStartupTimeoutSec = value
                                    timeoutExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            AppDialogActions(
                dismissText = stringResource(R.string.cancel),
                confirmText = stringResource(R.string.server_save),
                onDismiss = onDismiss,
                onConfirm = {
                    onSave(
                        localEnabled,
                        trimmedProxyUrl,
                        trimmedNoProxy,
                        localAllowLanAccess,
                        localServerUsername.trim(),
                        trimmedServerPassword,
                        localRunInBackground,
                        localAutoStart && localRunInBackground,
                        localStartupTimeoutSec,
                    )
                },
                confirmEnabled = canSave,
            )
        }
    }
}

private object FullStringMaskTransformation : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        if (raw.isEmpty()) {
            return androidx.compose.ui.text.input.TransformedText(text, androidx.compose.ui.text.input.OffsetMapping.Identity)
        }
        val masked = "\u2022".repeat(raw.length)
        return androidx.compose.ui.text.input.TransformedText(
            androidx.compose.ui.text.AnnotatedString(masked),
            androidx.compose.ui.text.input.OffsetMapping.Identity,
        )
    }
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
internal fun <K> SettingsPickerDialog(
    title: String,
    options: List<Pair<K, String>>,
    selectedKey: K,
    onSelect: (K) -> Unit,
    onDismiss: () -> Unit,
    maxHeight: Int = 480
) {
    val isAmoled = isAmoledTheme()

    val listState = rememberLazyListState()

    // Scroll to selected item on first composition
    val selectedIndex = remember(options, selectedKey) {
        options.indexOfFirst { it.first == selectedKey }.coerceAtLeast(0)
    }
    LaunchedEffect(selectedIndex) {
        listState.scrollToItem(selectedIndex)
    }

    AppDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.dp),
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
                                .clip(AppPickerItemShape)
                                .background(
                                    when {
                                        isSelected -> appSelectedItemColor()
                                        else -> Color.Transparent
                                    }
                                )
                                .then(
                                    if (isSelected && isAmoled) {
                                        Modifier.border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                                            shape = AppPickerItemShape,
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
                    AppSecondaryButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
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
private fun MessageHistoryResponseLimitPickerDialog(
    currentLimitMb: Int,
    onLimitSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsPickerDialog(
        title = stringResource(R.string.settings_history_response_limit),
        options = listOf(8, 16, 24, 32, 48, 64, 96, 128).map {
            it to stringResource(R.string.settings_history_response_limit_value, it)
        },
        selectedKey = currentLimitMb,
        onSelect = onLimitSelected,
        onDismiss = onDismiss,
    )
}

@Composable
private fun RecentDirectoryCountPickerDialog(
    currentCount: Int,
    onCountSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsPickerDialog(
        title = stringResource(R.string.settings_recent_directories),
        options = listOf(5, 10, 15, 20, 30, 50).map { it to "$it" },
        selectedKey = currentCount,
        onSelect = onCountSelected,
        onDismiss = onDismiss,
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
private fun HapticPatternDialog(
    currentDurationMillis: Int,
    currentAmplitude: Int,
    onSave: (durationMillis: Int, amplitude: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var durationMillis by remember(currentDurationMillis) {
        mutableFloatStateOf(currentDurationMillis.coerceIn(5, 100).toFloat())
    }
    var amplitude by remember(currentAmplitude) {
        mutableFloatStateOf(currentAmplitude.coerceIn(1, 255).toFloat())
    }
    val view = LocalView.current

    AppDialog(onDismissRequest = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_haptic_pattern), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_haptic_duration_value, durationMillis.roundToInt()),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = durationMillis,
                onValueChange = { durationMillis = it },
                valueRange = 5f..100f,
            )
            Text(
                stringResource(R.string.settings_haptic_amplitude_value, amplitude.roundToInt()),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = amplitude,
                onValueChange = { amplitude = it },
                valueRange = 1f..255f,
            )
            AppSecondaryButton(
                onClick = {
                    AppHaptics.perform(
                        view,
                        AppHapticConfig(
                            enabled = true,
                            durationMillis = durationMillis.roundToInt(),
                            amplitude = amplitude.roundToInt(),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                outlined = true,
            ) {
                Text(stringResource(R.string.settings_haptic_test))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppSecondaryButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                AppPrimaryButton(
                    onClick = { onSave(durationMillis.roundToInt(), amplitude.roundToInt()) },
                ) {
                    Text(stringResource(R.string.server_save))
                }
            }
        }
    }
}

@Composable
private fun TerminalFontSizeDialog(
    currentSize: Float,
    onSizeSelected: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(currentSize) { mutableFloatStateOf(currentSize.coerceIn(6f, 20f)) }

    AppDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_terminal_font_size),
                style = MaterialTheme.typography.titleMedium,
            )
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                AppSecondaryButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                AppPrimaryButton(onClick = { onSizeSelected(selected.roundToInt().toFloat()) }) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
}

@Composable
private fun ImageCompressionMaxSideDialog(
    currentMaxSide: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(0, 720, 960, 1080, 1440, 1920, 2560)
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
    val locale = Locale.forLanguageTag(code)
    
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
    if (px <= 0) {
        return stringResource(R.string.settings_compress_images_max_side_keep_original)
    }
    return stringResource(R.string.settings_compress_images_max_side_value, px)
}
