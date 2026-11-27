package app.bandmate.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.bandmate.AppFilter
import app.bandmate.Band
import app.bandmate.NotifListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun hasNotificationAccess(ctx: Context): Boolean {
    val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
    val me = ComponentName(ctx, NotifListener::class.java).flattenToString()
    return flat.split(':').any { it == me }
}

@Composable
fun AppsScreen(notifAccess: Boolean) {
    val ctx = LocalContext.current
    var forward by remember { mutableStateOf(Band.forwardNotifications(ctx)) }
    var query by remember { mutableStateOf("") }
    val overrides = remember { mutableStateMapOf<String, Boolean>() }
    val apps by produceState(emptyList<AppFilter.AppRow>()) { value = withContext(Dispatchers.IO) { AppFilter.installedApps(ctx) } }
    val (sent, blocked) = remember { AppFilter.todayCounts(ctx) }

    val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
    // Apps that are on go first. Worked out once per load, so rows don't jump while you toggle.
    val onAtLoad = remember(apps) { apps.filter { AppFilter.isAllowed(ctx, it.pkg) }.map { it.pkg }.toSet() }
    val filtered = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        .sortedByDescending { it.pkg in onAtLoad }
    val recent = filtered.filter { AppFilter.lastSeen(ctx, it.pkg) > weekAgo }.sortedByDescending { AppFilter.lastSeen(ctx, it.pkg) }
    val others = filtered - recent.toSet()

    fun allowed(pkg: String) = overrides[pkg] ?: AppFilter.isAllowed(ctx, pkg)
    fun set(pkg: String, on: Boolean) { overrides[pkg] = on; AppFilter.set(ctx, pkg, on) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Notifications", style = T.Title, modifier = Modifier.padding(top = 8.dp))
        if (!notifAccess) {
            Card(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Ic.Apps, C.Awake)
                    Spacer(Modifier.width(12.dp))
                    Text("Allow notification access so BandMate can pass messages to your band.", style = T.Label, modifier = Modifier.weight(1f))
                }
                PrimaryButton("Open settings", C.Awake, modifier = Modifier.fillMaxWidth()) {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            ToggleRow("Send notifications to the band", checked = forward) { forward = it; Band.setForward(ctx, it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountTile("$sent", "sent today", C.Steps, Modifier.weight(1f))
                CountTile("$blocked", "blocked today", C.Muted, Modifier.weight(1f))
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.Search, null, tint = C.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search apps", style = T.Body.copy(color = C.Faint))
                BasicTextField(query, { query = it }, singleLine = true, textStyle = T.Body, cursorBrush = SolidColor(C.Steps), modifier = Modifier.fillMaxWidth())
            }
        }
        if (recent.isNotEmpty()) {
            SectionHeader("Notified this week")
            AppList(recent, ::allowed, ::set, showCount = true)
        }
        SectionHeader(if (recent.isEmpty()) "All apps" else "Other apps")
        if (apps.isEmpty()) EmptyHint("Loading apps…") else AppList(others, ::allowed, ::set, showCount = false)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CountTile(value: String, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(C.Surface2).padding(12.dp)) {
        Text(value, style = T.Num.copy(color = color))
        Text(label, style = T.Label)
    }
}

@Composable
private fun AppList(rows: List<AppFilter.AppRow>, allowed: (String) -> Boolean, set: (String, Boolean) -> Unit, showCount: Boolean) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(CardShape).background(C.Surface).padding(vertical = 6.dp)) {
        rows.forEach { r ->
            val on = allowed(r.pkg)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(r)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.label, style = T.Body.copy(color = if (on) C.Text else C.Muted), maxLines = 1)
                    if (showCount) {
                        val n = AppFilter.seenCount(ctx, r.pkg)
                        Text("$n notification${if (n == 1) "" else "s"}", style = T.Small)
                    }
                }
                Toggle(on, { set(r.pkg, it) })
            }
        }
    }
}

@Composable
private fun AppIcon(r: AppFilter.AppRow) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, r.pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(r.info).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface2)) {
        bmp?.let { Image(it, null, Modifier.size(36.dp)) }
    }
}
