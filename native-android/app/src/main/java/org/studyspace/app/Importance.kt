package org.studyspace.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable fun ImportancePicker(value: Int?, onChange: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body("Importance"); Body(if (value == null) "N/A" else "$value / 5", color = Palette.sage) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Importance: N/A"; selected = value == null }.clickable(role = Role.Button) { onChange(null) }, contentAlignment = Alignment.Center) { Body("N/A", color = if (value == null) Palette.sage else Palette.muted) }
            (1..5).forEach { n ->
                Box(Modifier.weight(1f).height(48.dp).semantics { contentDescription = "Importance: $n ${if (n == 1) "star" else "stars"}"; selected = value == n }.clickable(role = Role.Button) { onChange(n) }, contentAlignment = Alignment.Center) {
                    Icon(if (value != null && n <= value) Icons.Outlined.Star else Icons.Outlined.StarBorder, null, tint = if (value != null && n <= value) Palette.sage else Palette.muted, modifier = Modifier.size(24.dp))
                }
            }
        }
        Body("Your priority for this item only. Change it anytime; confidence and completion stay independent.")
    }
}
@Composable fun ImportanceFilter(value: String, onChange: (String) -> Unit) = Choice("Filter by importance", value, importanceFilters, onChange)
@Composable fun TopicFilterControls(value: TopicFilter, onChange: (TopicFilter) -> Unit) {
    ImportanceFilter(value.importance) { onChange(value.copy(importance = it)) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) { Choice("Topic level", value.level, topicLevels) { onChange(value.copy(level = it)) } }
        Box(Modifier.weight(1f)) { Choice("Match stars from", value.source, listOf("self" to "This item's own stars", "parent" to "Its immediate parent's stars", "subject" to "Its subject's stars")) { onChange(value.copy(source = it)) } }
    }
}
@Composable fun ImportanceBadge(value: Int?) = Body(importanceLabel(value), color = if (value == null) Palette.muted else Palette.sage)
