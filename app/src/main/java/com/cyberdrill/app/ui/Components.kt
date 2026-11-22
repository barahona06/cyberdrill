package com.cyberdrill.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyberdrill.app.ai.AiSource

/**
 * The badge shown on EVERY AI (or seed) answer: where the answer came from,
 * read from Firebase AI Logic's `response.inferenceSource`. Judges and users
 * can always see whether the device or the cloud did the work.
 */
@Composable
fun InferenceBadge(source: AiSource, l: L, modifier: Modifier = Modifier) {
    val (label, color) = when (source) {
        AiSource.ON_DEVICE -> l.badgeOnDevice to Color(0xFF1B7A43)
        AiSource.IN_CLOUD -> l.badgeInCloud to Color(0xFF1565C0)
        AiSource.SEED_BANK -> l.badgeSeed to Color(0xFF616161)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(50), color = color, modifier = Modifier.size(8.dp)) {}
            Text(
                text = "  $label",
                color = color,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
