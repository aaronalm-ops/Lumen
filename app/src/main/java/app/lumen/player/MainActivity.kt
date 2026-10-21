package app.lumen.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import app.lumen.player.data.MediaPermissions
import app.lumen.player.data.backup.BackupManager
import app.lumen.player.diag.RingLog
import app.lumen.player.ui.DrivePickerActivity
import app.lumen.player.ui.LumenRoot
import app.lumen.player.ui.MainViewModel
import app.lumen.player.ui.RootActions
import com.google.android.gms.common.api.ApiException

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

    /**
     * The media permissions behind "Scan whole phone". Asked only from that tap (or the Home
     * empty state); the result is read back from checkSelfPermission so a partial video grant
     * on Android 14 and later is judged the same way everywhere.
     */
    private val requestMedia = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result: Map<String, Boolean> ->
        RingLog.i("ui", "media permission result ${result.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}")
        vm.onMediaPermissionResult()
    }

    // Phase 1b: Drive. Whether the sign-in sheet should be followed by the folder picker.
    private var pickAfterSignIn = false

    /** The Google sign-in sheet (AuthorizationResult.pendingIntent); the only sheet Drive ever shows. */
    private val driveSignIn = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val wantPicker = pickAfterSignIn
        pickAfterSignIn = false
        if (result.resultCode != Activity.RESULT_OK) {
            RingLog.i("drive", "sign-in sheet dismissed")
            return@registerForActivityResult
        }
        try {
            val auth = LumenApp.from(this).drive.auth
            val token = auth.accept(auth.resultFromIntent(result.data))
            RingLog.i("drive", "sign-in granted, token ${if (token == null) "missing" else "received"}")
            vm.onDriveSignedIn()
            if (wantPicker && token != null) drivePicker.launch(DrivePickerActivity.createIntent(this, token))
        } catch (e: ApiException) {
            RingLog.w("drive", "sign-in result failed: ${e.statusCode} ${e.message}")
        } catch (t: Throwable) {
            RingLog.w("drive", "sign-in result failed: $t")
        }
    }

    /** The WebView picker; returns the folder id and name. */
    private val drivePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val id = data.getStringExtra(DrivePickerActivity.EXTRA_FOLDER_ID).orEmpty()
            val name = data.getStringExtra(DrivePickerActivity.EXTRA_FOLDER_NAME).orEmpty()
            if (id.isNotBlank()) vm.setDriveFolder(id, name)
        } else {
            RingLog.i("drive", "picker closed: ${data?.getStringExtra(DrivePickerActivity.EXTRA_REASON) ?: "cancelled"}")
        }
    }

    // Phase 1b: backup, through the system document pickers only.
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) vm.backupTo(uri)
    }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.restoreFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash (black, the arc) must be installed before super.onCreate.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        RingLog.i("ui", "activity onCreate")
        vm = ViewModelProvider(this)[MainViewModel::class.java]

        val app = LumenApp.from(this)
        // The branded Compose screen shows once per process, on a cold start only.
        val showBrand = savedInstanceState == null && app.coldStart
        app.coldStart = false

        val actions = RootActions(
            addFolder = { openTree.launch(null) },
            scanPhone = {
                if (MediaPermissions.granted(this)) vm.scanMediaStore()
                else requestMedia.launch(MediaPermissions.required)
            },
            openAppSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                try {
                    startActivity(intent)
                } catch (t: Throwable) {
                    RingLog.w("ui", "app settings page failed: $t")
                }
            },
            openUpdate = { vm.update.value?.url?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } },
            openGitHub = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.GITHUB_OWNER}/lumen"))) },
            connectDrive = { startDriveAuthorisation(thenPick = true) },
            driveSignIn = { startDriveAuthorisation(thenPick = false) },
            backupNow = {
                try {
                    createBackup.launch(BackupManager.defaultFileName())
                } catch (t: Throwable) {
                    RingLog.w("backup", "create document failed: $t")
                }
            },
            restoreBackup = {
                try {
                    openBackup.launch(arrayOf("application/json", "text/plain", "*/*"))
                } catch (t: Throwable) {
                    RingLog.w("backup", "open document failed: $t")
                }
            },
        )

        setContent {
            LumenRoot(vm = vm, actions = actions, showBrand = showBrand)
        }

        if (savedInstanceState == null) handleShortcut(intent)
    }

    /**
     * Asks the AuthorizationClient for the drive.file grant. A held grant answers at once with
     * a token (no UI); a missing one hands back a resolution, shown as the Google sheet. With
     * [thenPick] the folder picker follows either way.
     */
    private fun startDriveAuthorisation(thenPick: Boolean) {
        if (BuildConfig.PICKER_API_KEY.isBlank()) return
        val auth = LumenApp.from(this).drive.auth
        auth.authorizeTask()
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        RingLog.w("drive", "resolution without a pending intent")
                        return@addOnSuccessListener
                    }
                    pickAfterSignIn = thenPick
                    try {
                        driveSignIn.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    } catch (t: Throwable) {
                        pickAfterSignIn = false
                        RingLog.w("drive", "sign-in sheet failed to open: $t")
                    }
                } else {
                    val token = auth.accept(result)
                    vm.onDriveSignedIn()
                    if (thenPick && token != null) drivePicker.launch(DrivePickerActivity.createIntent(this, token))
                    else if (thenPick) RingLog.w("drive", "authorised without a token; picker not opened")
                }
            }
            .addOnFailureListener { e ->
                RingLog.w("drive", "authorisation failed: $e")
            }
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
