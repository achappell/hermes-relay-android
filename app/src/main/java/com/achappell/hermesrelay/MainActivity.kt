package com.achappell.hermesrelay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme

class MainActivity : ComponentActivity() {
    private val clientPort: AndroidClientPort = BootstrapClientPort

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HermesRelayTheme {
                BootstrapScreen(clientPort.snapshot())
            }
        }
    }
}

@Composable
private fun BootstrapScreen(snapshot: AndroidClientSnapshot) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .safeDrawingPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(snapshot.titleRes),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = stringResource(snapshot.descriptionRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    modifier = Modifier.padding(20.dp),
                    text = stringResource(snapshot.boundaryRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
