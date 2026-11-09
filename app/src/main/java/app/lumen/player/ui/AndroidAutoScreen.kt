package app.lumen.player.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lumen.player.BuildConfig

@Composable
fun AndroidAutoScreen(back: () -> Unit, shareDiagnostics: () -> Unit) {
    val c = Lumen.colours
    val context = LocalContext.current
    val source = remember {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
        }.getOrNull()
    }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(c.base).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTap(LumenIcons.ChevronLeft, "Back", c.text, onClick = back)
            Text("Android Auto", style = Lumen.type.displayM, color = c.text)
        }
        LazyColumn(contentPadding = PaddingValues(Gutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Listen in the car", style = Lumen.type.displayS, color = c.text)
                Spacer(Modifier.height(8.dp))
                Text("Lumen offers Continue, Playlists, Sermons and Recent through Android Auto. Phone files and connected Drive recordings play through Lumen. YouTube Music plays through its own Android Auto app.", style = Lumen.type.body, color = c.text2)
            }
            item {
                ListCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("If Lumen is missing", style = Lumen.type.bodyStrong, color = c.text)
                        Text(if (source == "com.android.vending") "This copy was installed by Google Play. Check Android Auto's Customize launcher list and enable Lumen, then reconnect the car."
                            else "This copy was installed outside Google Play, or the installer is unknown. Android Auto may hide it until its media-app testing option is enabled.", style = Lumen.type.body, color = c.text2)
                        Text("1. On your phone, open Android Auto settings.\n2. Near the bottom, tap Version, then tap the version information 10 times and accept developer mode.\n3. Open the top-right menu, Developer settings, and enable Unknown sources.\n4. Reopen Android Auto settings, check Customize launcher for Lumen, and reconnect the car.", style = Lumen.type.body, color = c.text)
                        Text("Do this while parked. Google Play internal testing is the ongoing installation route; this app update cannot enable Android Auto's setting for you.", style = Lumen.type.bodyS, color = c.text3)
                    }
                }
            }
            item {
                SecondaryPill("Android Auto app settings", onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.google.android.projection.gearhead"))
                    runCatching { context.startActivity(intent) }.onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                })
                Spacer(Modifier.height(6.dp))
                Text("In app info, choose Additional settings in the app if offered. You can also search your phone's Settings for Android Auto.", style = Lumen.type.bodyS, color = c.text3)
            }
            item {
                Text("Check playback", style = Lumen.type.bodyStrong, color = c.text)
                Text("Open Lumen on the phone once and scan your files or connect Drive. In the car, open Lumen, choose a recording, then check play, pause, seek and resume. If Lumen appears but its library does not load, share diagnostics after trying it.", style = Lumen.type.body, color = c.text2)
                Spacer(Modifier.height(8.dp))
                SecondaryPill("Share diagnostics", onClick = shareDiagnostics)
                Text("Lumen ${BuildConfig.VERSION_NAME}", style = Lumen.type.bodyS, color = c.text3)
            }
        }
    }
}
