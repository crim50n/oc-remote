package dev.minios.ocremote.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

val SessionCategoryColorKeys = listOf(
    "red", "orange", "amber", "green", "teal", "blue", "violet", "pink",
)

val SessionCategoryIconKeys = listOf(
    "work", "home", "bug", "idea", "research", "urgent", "code", "docs", "star", "build",
)

fun sessionCategoryColor(key: String): Color = when (key) {
    "red" -> Color(0xFFE85D68)
    "orange" -> Color(0xFFF28C48)
    "amber" -> Color(0xFFE4B740)
    "green" -> Color(0xFF55A96B)
    "teal" -> Color(0xFF3BA7A0)
    "blue" -> Color(0xFF4F8FEA)
    "violet" -> Color(0xFF8A6DE9)
    "pink" -> Color(0xFFD86FA8)
    else -> Color(0xFF4F8FEA)
}

fun sessionCategoryIcon(key: String): ImageVector = when (key) {
    "work" -> Icons.Default.Work
    "home" -> Icons.Default.Home
    "bug" -> Icons.Default.BugReport
    "idea" -> Icons.Default.Lightbulb
    "research" -> Icons.Default.Science
    "urgent" -> Icons.Default.PriorityHigh
    "code" -> Icons.Default.Code
    "docs" -> Icons.Default.Description
    "star" -> Icons.Default.Star
    "build" -> Icons.Default.Build
    else -> Icons.Default.Label
}
