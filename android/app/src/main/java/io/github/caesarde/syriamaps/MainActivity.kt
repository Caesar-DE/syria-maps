package io.github.caesarde.syriamaps

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.caesarde.syriamaps.osmand.OsmAndApps
import io.github.caesarde.syriamaps.ui.MainScreen
import io.github.caesarde.syriamaps.ui.SyriaMapsTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    // OsmAnd's permission screen identifies the caller only through startActivityForResult.
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onPermissionResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyriaMapsTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val pending by vm.intents.collectAsStateWithLifecycle()
                LaunchedEffect(pending) {
                    val intent = pending ?: return@LaunchedEffect
                    vm.intentHandled()
                    try {
                        if (intent.action == OsmAndApps.ACTION_REQUEST_PERMISSIONS) permissionLauncher.launch(intent)
                        else startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        vm.intentFailed(intent)
                    }
                }
                MainScreen(state, vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // OsmAnd may have been installed, updated or stopped while we were in the background.
        vm.refreshOsmAnd()
    }
}
