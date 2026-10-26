package dev.minios.ocremote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import dev.minios.ocremote.R

@Composable
fun SessionTabsButton(count: Int, onClick: () -> Unit) {
    if (!LocalSessionFeatures.current.tabs) return
    val description = stringResource(R.string.session_tabs_count, count)
    IconButton(onClick = onClick) {
        Row(
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(Lucide.Copy, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelSmall)
        }
    }
}
