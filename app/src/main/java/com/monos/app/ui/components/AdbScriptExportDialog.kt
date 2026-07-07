package com.monos.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monos.app.ui.theme.*

@Composable
fun AdbScriptExportDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scriptContent = """
        adb shell device_config set_sync_disabled_for_tests persistent
        adb shell device_config put activity_manager max_phantom_processes 2147483647
        adb shell settings put global max_phantom_processes 2147483647
        adb shell device_config put activity_manager settings_to_monitor ""
    """.trimIndent()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Monos PPK ADB Script", scriptContent)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Script copied to clipboard!", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = PrimaryNeon)
            ) {
                Text("Copy Script", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        },
        title = {
            Text(
                "Manual ADB Export Command",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "If Wireless Debugging is unavailable on your device, execute these commands via a computer terminal connected to your phone:",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF07080D), RoundedCornerShape(8.dp))
                        .border(1.dp, SurfaceLight, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = scriptContent,
                        color = PrimaryNeon,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
            }
        },
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    )
}
