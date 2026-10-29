package app.spotxero.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.spotxero.ui.component.navigation.SpotXeroApp
import app.spotxero.ui.theme.AppTheme
import app.spotxero.ui.viewmodel.DashboardViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = uiState.isDarkTheme(systemDark)
            val snackbarHostState = remember { SnackbarHostState() }

            AppTheme(
                darkTheme = darkTheme,
                dynamicColor = uiState.isDynamicColorEnabled,
                amoled = uiState.isAppAmoledEnabled
            ) {
                SpotXeroApp(
                    viewModel = viewModel,
                    uiState = uiState,
                    darkTheme = darkTheme,
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStatus()
    }
}
