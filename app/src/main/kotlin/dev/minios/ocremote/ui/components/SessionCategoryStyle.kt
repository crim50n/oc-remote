package dev.minios.ocremote.ui.components

import com.composables.icons.lucide.*

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

val SessionCategoryColorKeys = listOf(
    "red", "orange", "amber", "green", "teal", "blue", "violet", "pink",
)

val SessionCategoryIconKeys = listOf(
    "work", "home", "bug", "idea", "research", "urgent", "code", "docs", "star", "build",
    "personal", "team", "project", "calendar", "clock", "bookmark", "flag", "heart", "rocket",
    "target", "security", "database", "server", "terminal", "git", "chat", "package", "layers",
    "web", "notification", "private", "archive",
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
    "work" -> Lucide.Briefcase
    "home" -> Lucide.House
    "bug" -> Lucide.Bug
    "idea" -> Lucide.Lightbulb
    "research" -> Lucide.FlaskConical
    "urgent" -> Lucide.CircleAlert
    "code" -> Lucide.Code
    "docs" -> Lucide.FileText
    "star" -> Lucide.Star
    "build" -> Lucide.Wrench
    "personal" -> Lucide.User
    "team" -> Lucide.Users
    "project" -> Lucide.FolderKanban
    "calendar" -> Lucide.Calendar
    "clock" -> Lucide.Clock
    "bookmark" -> Lucide.Bookmark
    "flag" -> Lucide.Flag
    "heart" -> Lucide.Heart
    "rocket" -> Lucide.Rocket
    "target" -> Lucide.Target
    "security" -> Lucide.Shield
    "database" -> Lucide.Database
    "server" -> Lucide.Server
    "terminal" -> Lucide.Terminal
    "git" -> Lucide.GitBranch
    "chat" -> Lucide.MessageCircle
    "package" -> Lucide.Package
    "layers" -> Lucide.Layers
    "web" -> Lucide.Globe
    "notification" -> Lucide.Bell
    "private" -> Lucide.Lock
    "archive" -> Lucide.Archive
    else -> Lucide.Tag
}
