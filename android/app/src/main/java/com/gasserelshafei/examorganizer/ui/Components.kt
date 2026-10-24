@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gasserelshafei.examorganizer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

val Brand = Color(0xFF2575FC)
val Purple = Color(0xFF6A11CB)
val Good = Color(0xFF2E7D32)
val Warn = Color(0xFFE65100)

@Composable
fun ExamOrganizerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colours = if (dark) darkColorScheme(primary = Color(0xFF8AB4FF), secondary = Color(0xFFC7A6FF), tertiary = Color(0xFF7FD4FF))
    else lightColorScheme(primary = Brand, secondary = Purple, tertiary = Color(0xFF0091C2))
    MaterialTheme(colorScheme = colours, content = content)
}

/** A rounded panel with a numbered title, like the desktop app's cards. */
@Composable
fun SectionCard(title: String?, number: Int? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (number != null) {
                        Box(Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                            Text("$number", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            content()
        }
    }
}

@Composable
fun Pill(text: String, colour: Color = MaterialTheme.colorScheme.primary, icon: ImageVector? = null) {
    Row(
        Modifier.background(colour.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = colour, modifier = Modifier.size(14.dp))
        Text(text, color = colour, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outline)
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

enum class RowStatus { WORKING, FILE, DONE, WARNING }

@Composable
fun StatusIcon(status: RowStatus) {
    Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
        when (status) {
            RowStatus.WORKING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
            RowStatus.FILE -> Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary)
            RowStatus.DONE -> Icon(Icons.Filled.CheckCircle, null, tint = Good)
            RowStatus.WARNING -> Icon(Icons.Filled.Warning, null, tint = Warn)
        }
    }
}

/** A button that opens a list to pick from. */
@Composable
fun <T> ChoiceButton(label: String, options: List<Pair<T, String>>, onPick: (T) -> Unit, modifier: Modifier = Modifier,
                     enabled: Boolean = true, leading: ImageVector? = null) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            if (leading != null) {
                Icon(leading, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (options.isEmpty()) DropdownMenuItem(text = { Text("Nothing to choose yet") }, onClick = { open = false }, enabled = false)
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}

@Composable
fun Note(text: String, colour: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = colour)
}
