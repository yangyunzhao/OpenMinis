package com.openminis.app.ui.settings

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.openminis.app.R

/**
 * OpenAI 设备授权专用等待界面。这里只接收 UI 必需的用户码和公开验证网址，不接收
 * device_auth_id、PKCE、authorization code 或 Token。
 */
@Composable
fun OpenAIDeviceLoginDialog(
    attemptId: Long,
    userCode: String,
    verificationUrl: String,
    claimAutomaticBrowserOpen: (Long) -> Boolean,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var codeCopied by remember(userCode) { mutableStateOf(false) }
    var urlCopied by remember(verificationUrl) { mutableStateOf(false) }
    var browserOpenFailed by remember(verificationUrl) { mutableStateOf(false) }

    fun openBrowser() {
        browserOpenFailed = !runCatching {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, verificationUrl.toUri())
        }.isSuccess
    }

    // 只能在 composition 已提交后消费一次性标志。若在调用本 Composable 时先 claim，
    // 一次被放弃的 composition 会永久吞掉本次 attempt 的自动打开机会。
    LaunchedEffect(attemptId, verificationUrl) {
        if (claimAutomaticBrowserOpen(attemptId)) openBrowser()
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.openai_device_login_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.openai_device_login_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    verificationUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString(verificationUrl))
                            urlCopied = true
                        }
                        .padding(vertical = 8.dp),
                ) {
                    Icon(
                        if (urlCopied) Icons.Default.CheckCircle else Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (urlCopied) {
                                R.string.openai_device_url_copied
                            } else {
                                R.string.openai_device_copy_url
                            },
                        ),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString(userCode))
                            codeCopied = true
                        },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                    ) {
                        Text(
                            userCode,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Icon(
                            if (codeCopied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.openai_device_copy_code),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (codeCopied) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.openai_device_code_copied),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openBrowser() }
                        .padding(vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.openai_device_open_browser),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (browserOpenFailed) {
                    Text(
                        stringResource(R.string.openai_device_browser_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.openai_device_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
