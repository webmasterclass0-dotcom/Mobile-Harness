package com.jarves.mh.control

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarves.mh.ui.theme.PocketTheme

/**
 * A small settings screen for the "agent controls the phone" feature.
 *
 * There are only two real requirements:
 *  1. Enable "Phone control" here (master switch).
 *  2. Enable the "Mobile Harness" accessibility service in the system settings
 *     (button below) so gestures and screen reads are possible.
 */
class PhoneControlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketTheme {
                PhoneControlScreen()
            }
        }
    }
}

@Composable
private fun PhoneControlScreen() {
    val context = LocalContext.current
    val settings = remember { PhoneControlSettings(context) }

    var enabled by remember { mutableStateOf(settings.enabled) }
    var requireToken by remember { mutableStateOf(settings.requireToken) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI agent controls this phone", style = MaterialTheme.typography.titleLarge)

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Phone control", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Lets the coding agent tap, swipe, type, open apps and read the screen.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            settings.enabled = it
                            PhoneControlServer.refresh(context)
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Require access token", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Reject requests without X-Access-Token. Keep this on: any app " +
                                "on the device can reach the local server.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = requireToken,
                        onCheckedChange = {
                            requireToken = it
                            settings.requireToken = it
                        },
                    )
                }
            }
        }

        Button(
            onClick = {
                Toast.makeText(context, "Enable the “Mobile Harness” service there", Toast.LENGTH_SHORT).show()
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open accessibility settings")
        }

        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                })
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Modify battery / background restrictions")
        }

        val token = settings.issueTokenIfNeeded()
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                StatusRow("Control server", phoneStatus(context))
                StatusRow("Accessibility service", PhoneControlService.isConnected().asYesNo())
                StatusRow("Port (agent env MH_PHONE_PORT)", PhoneControlRuntime.port.takeIf { it > 0 }?.toString() ?: "off")
                Text(
                    "Inside the agent terminal, the helper is called «phone»:  phone ui, " +
                        "phone tap --text “Open”, phone type “hello”, phone swipe …",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("phone token", token))
                        Toast.makeText(context, "Token copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Copy access token")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private fun phoneStatus(context: Context): String {
    val settings = PhoneControlSettings(context)
    return when {
        !settings.enabled -> "disabled"
        PhoneControlServer.isRunning() -> "running (127.0.0.1:${PhoneControlRuntime.port})"
        else -> "starting…"
    }
}

private fun Boolean.asYesNo(): String = if (this) "connected" else "not connected"