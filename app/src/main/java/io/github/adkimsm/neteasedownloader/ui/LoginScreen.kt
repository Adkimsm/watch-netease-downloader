package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.net.QrcodeStatus
import io.github.adkimsm.neteasedownloader.ui.components.PrimaryButton
import io.github.adkimsm.neteasedownloader.ui.components.StateBadge
import io.github.adkimsm.neteasedownloader.ui.theme.BrandRed
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.Spacing
import io.github.adkimsm.neteasedownloader.ui.theme.WindowClass
import io.github.adkimsm.neteasedownloader.ui.theme.StateError
import io.github.adkimsm.neteasedownloader.ui.theme.StateInfo
import io.github.adkimsm.neteasedownloader.ui.theme.StateOk
import io.github.adkimsm.neteasedownloader.ui.theme.StatePending
import io.github.adkimsm.neteasedownloader.ui.theme.StateWarn
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 登录页。改为上下结构:二维码在上、状态与操作在下 ——
 * 原实现左右并排在窄屏会把文字挤扁。
 *
 * "已扫码待确认"是最需要反馈的时刻(用户正盯着手表等),故与"等待扫码"用不同颜色和文案区分。
 */
@Composable
fun LoginScreen(state: LoginUiState, onRefresh: () -> Unit) {
    val sizing = LocalWindowSizing.current
    // 二维码是登录页唯一的操作入口,窄窗口下收窄一档,避免被状态文案挤出屏幕
    val qrWidthFraction = if (sizing.windowClass == WindowClass.Compact) 0.5f else 0.58f
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (state) {
            is LoginUiState.Loading -> CenteredColumn {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = BrandRed,
                )
                Spacer(Modifier.height(sizing.gapSm))
                Text(
                    text = stringResource(R.string.login_generating_qr),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }

            is LoginUiState.Waiting -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(sizing.gapMd),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // 二维码必须白底黑码,故保留白色卡片 + 留白(quiet zone)
                Surface(
                    shape = RoundedCornerShape(Spacing.sm),
                    color = Color.White,
                ) {
                    Image(
                        painter = BitmapPainter(state.qrBitmap.asImageBitmap()),
                        contentDescription = stringResource(R.string.login_qr_content_desc),
                        modifier = Modifier
                            .fillMaxWidth(qrWidthFraction)
                            .padding(sizing.gapSm),
                    )
                }
                Spacer(Modifier.height(sizing.gapMd))

                val (badgeColor, badgeText) = statusAppearance(state)
                StateBadge(text = badgeText, color = badgeColor)

                if (state.status == QrcodeStatus.EXPIRED) {
                    Spacer(Modifier.height(sizing.gapMd))
                    PrimaryButton(
                        text = stringResource(R.string.login_refresh_qr),
                        onClick = onRefresh,
                        modifier = Modifier.fillMaxWidth(0.8f),
                    )
                }
            }

            is LoginUiState.Success -> CenteredColumn {
                StateBadge(
                    text = stringResource(R.string.login_success),
                    color = StateOk,
                )
                state.account?.nickname?.let { nickname ->
                    Spacer(Modifier.height(sizing.gapSm))
                    Text(
                        text = nickname,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                    )
                }
            }

            is LoginUiState.Error -> CenteredColumn {
                Text(
                    text = stringResource(R.string.login_expired),
                    style = MaterialTheme.typography.titleMedium,
                    color = StateError,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(sizing.gapMd))
                PrimaryButton(
                    text = stringResource(R.string.login_refresh_qr),
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(0.8f),
                )
            }
        }
    }
}

/**
 * 按扫码状态给出颜色与文案:色 + 文案双重编码,不单靠颜色。
 * "已扫码待确认"用警示色,因为此刻用户会一直盯着手表等结果。
 */
@Composable
private fun statusAppearance(state: LoginUiState.Waiting): Pair<Color, String> = when (state.status) {
    QrcodeStatus.SCANNED -> StateWarn to stringResource(R.string.login_scanned)
    QrcodeStatus.EXPIRED -> StateError to stringResource(R.string.login_expired)
    QrcodeStatus.SUCCESS -> StateOk to stringResource(R.string.login_success)
    QrcodeStatus.UNKNOWN -> StatePending to state.message
    else -> StateInfo to state.message
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    val sizing = LocalWindowSizing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(sizing.gapMd),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}
