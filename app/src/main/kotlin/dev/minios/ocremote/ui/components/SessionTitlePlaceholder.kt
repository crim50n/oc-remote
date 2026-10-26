package dev.minios.ocremote.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R

@Composable
fun SessionTitlePlaceholder(loading: Boolean) {
    val opacity = if (loading) {
        val transition = rememberInfiniteTransition(label = "session title loading")
        val alpha by transition.animateFloat(
            initialValue = 0.10f,
            targetValue = 0.22f,
            animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
            label = "placeholder opacity",
        )
        alpha
    } else 0.10f
    val description = stringResource(if (loading) R.string.loading else R.string.session_tabs_offline)
    Box(
        Modifier.widthIn(max = 180.dp).fillMaxWidth(0.7f).height(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = opacity))
            .semantics { contentDescription = description },
    )
}
