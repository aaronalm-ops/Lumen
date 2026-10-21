package app.lumen.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import app.lumen.player.diag.RingLog
import app.lumen.player.ui.LumenRoot
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.RootActions

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    companion object {
        /** Extra carried by the static app shortcuts: "resume", "worship" or "kids". */
        const val EXTRA_SHORTCUT = "app.lumen.player.shortcut"
    }

    private lateinit var vm: MainViewModel

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            RingLog.i("ui", "folder picked $uri")
            vm.addFolder(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        RingLog.i("ui", "activity onCreate")
        vm = ViewModelProvider(this)[MainViewModel::class.java]

        val actions = RootActions(
            addFolder = { openTree.launch(null) },
            openUpdate = { vm.update.value?.url?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } },
            openGitHub = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.GITHUB_OWNER}/lumen"))) },
        )

        setContent {
            LumenRoot(vm = vm, actions = actions)
        }

        if (savedInstanceState == null) handleShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcut(intent)
    }

    private fun handleShortcut(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_SHORTCUT) ?: return
        RingLog.i("ui", "shortcut $action")
        vm.handleShortcut(action)
        intent.removeExtra(EXTRA_SHORTCUT)
    }

    override fun onStart() {
        super.onStart()
        vm.connect(this)
    }

    override fun onStop() {
        vm.disconnect()
        super.onStop()
    }
}
