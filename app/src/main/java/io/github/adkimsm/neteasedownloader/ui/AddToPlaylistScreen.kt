package io.github.adkimsm.neteasedownloader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.adkimsm.neteasedownloader.R
import io.github.adkimsm.neteasedownloader.library.AddTarget
import io.github.adkimsm.neteasedownloader.ui.components.PrimaryButton
import io.github.adkimsm.neteasedownloader.ui.components.ScreenScaffold
import io.github.adkimsm.neteasedownloader.ui.components.SecondaryButton
import io.github.adkimsm.neteasedownloader.ui.theme.LocalWindowSizing
import io.github.adkimsm.neteasedownloader.ui.theme.TextDisabled
import io.github.adkimsm.neteasedownloader.ui.theme.TextPrimary
import io.github.adkimsm.neteasedownloader.ui.theme.TextSecondary

/**
 * 把这首歌加入某些歌单。
 *
 * 候选里**不含「我喜欢的音乐」**:红心走 radio/like,让它出现在这里会让用户以为
 * 可以通过"加歌单"来红心一首歌 —— 那是一条走不通的路。
 */
@Composable
fun AddToPlaylistScreen(
    songName: String,
    targets: List<AddTarget>,
    submitting: Boolean,
    onBack: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    val sizing = LocalWindowSizing.current
    var picked by remember(targets) { mutableStateOf(emptySet<Long>()) }

    ScreenScaffold(title = stringResource(R.string.actions_add_to_playlist), onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = songName,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(sizing.gapSm))

            if (targets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.add_no_targets),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                SecondaryButton(text = stringResource(R.string.common_back), onClick = onBack)
                return@ScreenScaffold
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                targets.forEach { target ->
                    val checked = target.playlistId in picked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = sizing.menuRowHeight)
                            .padding(end = sizing.gapSm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(sizing.touchTarget * 0.75f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    picked = if (on) {
                                        picked + target.playlistId
                                    } else {
                                        picked - target.playlistId
                                    }
                                },
                                enabled = !submitting,
                                modifier = Modifier.size(sizing.iconSize),
                            )
                        }
                        Text(
                            text = target.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (target.owned) TextPrimary else TextDisabled,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(sizing.gapSm))
            PrimaryButton(
                text = stringResource(R.string.add_confirm, picked.size),
                onClick = { onConfirm(picked.toList()) },
                enabled = picked.isNotEmpty() && !submitting,
                loading = submitting,
            )
            Spacer(Modifier.height(sizing.gapSm))
            SecondaryButton(text = stringResource(R.string.common_cancel), onClick = onBack)
        }
    }
}
