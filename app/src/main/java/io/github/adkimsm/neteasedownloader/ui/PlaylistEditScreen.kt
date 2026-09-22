package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.library.NameCheck
import io.github.adkimsm.neteasedownloader.library.PLAYLIST_NAME_MAX
import io.github.adkimsm.neteasedownloader.library.checkPlaylistName
import io.github.adkimsm.neteasedownloader.ui.components.PrimaryButton
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.components.SecondaryButton
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.StateError
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 新建 / 重命名歌单。
 *
 * 校验放在**本地**(见 checkPlaylistName):手表上敲字本来就难受,
 * 提交完才告诉用户"名字不能为空"是最差的一种反馈。
 */
@Composable
fun PlaylistEditScreen(
    title: String,
    initialName: String,
    submitting: Boolean,
    remoteError: String?,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val sizing = LocalWindowSizing.current
    var text by remember(initialName) { mutableStateOf(initialName) }
    var localError by remember { mutableStateOf<NameCheck?>(null) }

    val check = checkPlaylistName(text)
    val errorText = when {
        localError == NameCheck.Blank -> stringResource(R.string.playlist_name_blank)
        localError == NameCheck.TooLong -> stringResource(R.string.playlist_name_too_long, PLAYLIST_NAME_MAX)
        remoteError != null -> remoteError
        else -> null
    }

    ScreenScaffold(title = title, onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    localError = null
                },
                singleLine = true,
                isError = errorText != null,
                label = { Text(stringResource(R.string.playlist_name_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = sizing.touchTarget),
            )
            Spacer(Modifier.height(sizing.gapSm))

            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = StateError,
                )
            } else {
                Text(
                    text = stringResource(R.string.playlist_name_counter, text.length, PLAYLIST_NAME_MAX),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }

            Spacer(Modifier.height(sizing.gapMd))
            PrimaryButton(
                text = stringResource(R.string.common_confirm),
                onClick = {
                    when (check) {
                        is NameCheck.Ok -> onSubmit(check.name)
                        // 本地就能判定的错误不发请求,直接标在输入框上
                        else -> localError = check
                    }
                },
                enabled = check is NameCheck.Ok && !submitting,
                loading = submitting,
            )
            Spacer(Modifier.height(sizing.gapSm))
            SecondaryButton(text = stringResource(R.string.common_cancel), onClick = onBack)
        }
    }
}
