package app.bandmate.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bandmate.Clock

val CardShape = RoundedCornerShape(22.dp)

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.98f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    Column(
        modifier
            .scale(s)
            .clip(CardShape)
            .background(C.Surface)
            .then(if (onClick != null) Modifier.clickable(src, null, onClick = onClick) else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 34.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.56f)) }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = T.Section, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, accent: Color = C.Steps) {
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = C.Bg, checkedTrackColor = accent, checkedBorderColor = accent,
            uncheckedThumbColor = C.Muted, uncheckedTrackColor = C.Surface2, uncheckedBorderColor = C.Line,
        ),
    )
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, accent: Color = C.Steps, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.Body)
            if (subtitle != null) Text(subtitle, style = T.Label)
        }
        Spacer(Modifier.width(12.dp))
        Toggle(checked, onChange, accent)
    }
}

/** A row of options with a sliding highlight behind the chosen one. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, accent: Color = C.Text, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface2).padding(3.dp)
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * index, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "seg")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(C.Line))
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, (value, label) ->
                val color by animateColorAsState(if (i == index) accent else C.Muted, label = "segc")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = T.Label.copy(color = color)) }
            }
        }
    }
}

@Composable
fun Pill(text: String, color: Color = C.Text, bg: Color = C.Surface2, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = T.Label.copy(color = color))
    }
}

@Composable
fun PrimaryButton(text: String, accent: Color = C.Steps, enabled: Boolean = true, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (enabled) accent else C.Surface2, label = "pb")
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (enabled) C.Bg else C.Faint
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = T.Body.copy(color = fg))
    }
}

@Composable
fun GhostButton(text: String, color: Color = C.Text, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, C.Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = T.Body.copy(color = color))
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

/** A time like "07:30" that opens a clock picker when tapped. */
@Composable
fun TimeField(value: Clock, accent: Color = C.Text, onChange: (Clock) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(
        value.toString(), style = T.Num.copy(color = accent),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Surface2).clickable { open = true }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
    if (open) TimeDialog(value, accent, onDismiss = { open = false }) { open = false; onChange(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: Clock, accent: Color = C.Steps, onDismiss: () -> Unit, onPick: (Clock) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        confirmButton = { TextButton({ onPick(Clock.of(state.hour, state.minute)) }) { Text("Set", color = accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = C.Muted) } },
        text = {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = C.Surface2, selectorColor = accent, containerColor = C.Surface,
                    timeSelectorSelectedContainerColor = accent.copy(alpha = 0.18f), timeSelectorSelectedContentColor = accent,
                    timeSelectorUnselectedContainerColor = C.Surface2, timeSelectorUnselectedContentColor = C.Text,
                    clockDialSelectedContentColor = C.Bg, clockDialUnselectedContentColor = C.Text,
                ),
            )
        },
    )
}

/** "From 22:00 to 07:00" with tappable times. */
@Composable
fun TimeRange(start: Clock, end: Clock, accent: Color = C.Text, onStart: (Clock) -> Unit, onEnd: (Clock) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("From", style = T.Label); TimeField(start, accent, onStart)
        Text("to", style = T.Label); TimeField(end, accent, onEnd)
    }
}

fun formatMinutes(m: Int): String = if (m < 60) "${m}m" else "${m / 60}h ${"%02d".format(m % 60)}m"
