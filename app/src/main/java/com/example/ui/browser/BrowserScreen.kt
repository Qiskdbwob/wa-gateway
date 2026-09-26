package com.example.ui.browser

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.AgentEmerald
import com.example.wagateway.WaGatewayViewModel

/**
 * Browser automation screen.
 *
 * It shows the *same* live session the agent's browser tools drive, which is what makes the
 * captcha / 2FA handoff real: when the agent cannot continue on its own it asks the user, the
 * user finishes the step right here, and presses "Selesai" so the agent can resume.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: WaGatewayViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pending by viewModel.browserPendingUserAction.collectAsState()
    val enabled by viewModel.browserEnabled.collectAsState()
    val context = LocalContext.current

    // While this screen is open the WebView is created/hosted with the Activity context.
    DisposableEffect(Unit) {
        viewModel.bindBrowserHostContext(context)
        onDispose { viewModel.bindBrowserHostContext(null) }
    }

    var refreshKey by remember { mutableStateOf(0) }
    // Recomputed on every recomposition (and when the user asks for a refresh) so a session
    // opened by the agent shows up as soon as it exists.
    val webView = viewModel.browserView()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Browser Agent",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (enabled) "Automation aktif" else "Automation nonaktif (Pengaturan → Browser)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Segarkan tampilan")
                    }
                    IconButton(onClick = { viewModel.clearBrowserSession() }) {
                        Icon(Icons.Default.Logout, contentDescription = "Bersihkan sesi")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            pending?.let { request ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .testTag("browser_user_action_card"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "🙋 Agent menunggu Anda",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF92400E)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = request.instruction,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF92400E)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.completeBrowserUserAction() },
                                modifier = Modifier.testTag("browser_user_action_done"),
                                colors = ButtonDefaults.buttonColors(containerColor = AgentEmerald)
                            ) {
                                Text("Selesai — lanjutkan agent")
                            }
                            TextButton(onClick = { viewModel.abandonBrowserUserAction() }) {
                                Text("Batalkan")
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (webView == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Belum ada halaman yang dibuka.\n" +
                                "Minta agent menelusuri sesuatu (atau pakai /browser <url> di chat), " +
                                "lalu halaman itu akan tampil di sini.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                } else {
                    // A fresh FrameLayout each time keeps Compose's AndroidView happy while the
                    // long-lived WebView (owned by the engine) is re-parented into it.
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                addView(
                                    webView,
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp)
                    )
                }
            }

            Text(
                text = "Sesi ini dipakai bersama oleh agent. Jangan tutup langkah login/captcha di tengah jalan.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
