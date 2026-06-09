package com.netchecker.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.netchecker.data.database.NetCheckerDatabase
import com.netchecker.domain.usecase.CloudflareScannerUseCase
import com.netchecker.domain.usecase.PingUseCase
import com.netchecker.domain.usecase.ProxyOptimizerUseCase

/**
 * Main Android Entrance Activity.
 * Initializes background workers, instantiates the local Room databases securely,
 * and sets the Cyberpunk-styled Material Compose viewport.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize SQLite/Room resources
        val database = NetCheckerDatabase.getDatabase(this)
        
        // Instantiate use-case nodes
        val scannerUseCase = CloudflareScannerUseCase(database.cleanIpDao())
        val pingUseCase = PingUseCase()
        val proxyOptimizerUseCase = ProxyOptimizerUseCase(database.cleanIpDao(), database.proxyConfigDao())
        val batchTestConfigsUseCase = com.netchecker.domain.usecase.BatchTestConfigsUseCase(database.proxyConfigDao())

        // Create presentation state machine
        val viewModel = NetCheckerViewModel(
            cleanIpDao = database.cleanIpDao(),
            proxyConfigDao = database.proxyConfigDao(),
            scannerUseCase = scannerUseCase,
            pingUseCase = pingUseCase,
            proxyOptimizerUseCase = proxyOptimizerUseCase,
            batchTestConfigsUseCase = batchTestConfigsUseCase
        )

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = DarkBackground,
                    surface = ObsidianGray,
                    primary = NeonAqua,
                    secondary = NeonLime,
                    tertiary = NeonPink
                )
            ) {
                CyberpunkDashboard(viewModel = viewModel)
            }
        }
    }
}
