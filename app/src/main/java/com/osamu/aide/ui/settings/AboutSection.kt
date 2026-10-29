package com.osamu.aide.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osamu.aide.BuildConfig
import com.osamu.aide.core.ui.BrandMark
import com.osamu.aide.core.ui.progressTrackColor
import com.osamu.aide.update.AppUpdates
import com.osamu.aide.update.UpdateState
import com.osamu.aide.update.describe
import com.osamu.aide.update.openReleasePage

/**
 * What this is, what it is licensed under, and what else is inside it.
 *
 * **Not decoration.** AIDE-OS is GPLv3 and ships or downloads a dozen things
 * somebody else wrote — an LGPL editor widget, a vendored Apache-2.0 terminal,
 * a GPLv2-with-Classpath-exception JDK. Naming them is an obligation, and the
 * one place a user can look is the app itself.
 *
 * The version is read from the installed package rather than from a constant,
 * so it is the version that is actually running rather than the one someone
 * last remembered to update in a string.
 */
@Composable
fun AboutSection(updates: AppUpdates, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember(context) { installedVersion(context) }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = PanelSpacing,
    ) {
        SettingsPanel {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(size = 56.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text("AIDE-OS", style = MaterialTheme.typography.headlineSmall)
                    // Omitted rather than shown empty: a version that could not
                    // be read is not worth a line saying "Version ".
                    if (version.isNotEmpty()) {
                        Text(
                            text = "Version $version",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    builtFrom()?.let { commit ->
                        Text(
                            text = commit,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = "An on-device IDE for phones and tablets. It builds real Android " +
                    "apps with no root, no Termux and no Linux userland.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            )
        }

        UpdatesPanel(updates)

        SettingsPanel {
            Column(Modifier.padding(16.dp)) {
                Text("Licence", style = MaterialTheme.typography.titleSmall)
                Text(
                    // The exact words matter: this is the notice GPLv3 §4 asks
                    // for, and paraphrasing a warranty disclaimer is how it
                    // stops being one.
                    text = "GNU General Public License, version 3 or later. This program comes " +
                        "with ABSOLUTELY NO WARRANTY. It is free software, and you are welcome " +
                        "to redistribute it under the terms of that licence — the full text " +
                        "ships as LICENSE in the source repository.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        SettingsPanel {
            Column(Modifier.padding(16.dp)) {
                Text("Built on other people's work", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "The editor widget is sora-editor (LGPL-2.1), used unmodified. The " +
                        "terminal is Termux's emulator (Apache-2.0), vendored byte-identical. " +
                        "aapt2 comes from AOSP and ECJ from Eclipse; the compilers and " +
                        "runtimes this app downloads — Kotlin, clang, OpenJDK, Node, Mono — " +
                        "keep their own licences. NOTICE.md in the source repository lists " +
                        "every one and where that licence is recorded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * The version of this install, or nothing at all.
 *
 * `getPackageInfo` can throw for a package being replaced, and a version is not
 * worth an exception on a settings screen -- so the name simply loses its
 * suffix rather than the section losing its heading. Internal because the
 * Settings hub shows it too.
 */
internal fun installedVersion(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

/**
 * "Built from 147a159", the commit a bug report needs.
 *
 * Says when the tree had uncommitted changes, because then the commit alone
 * does not reproduce the build -- the case for every build made at a desk.
 */
private fun builtFrom(): String? {
    val commit = BuildConfig.GIT_COMMIT.take(7).ifEmpty { return null }
    return if (BuildConfig.GIT_LOCAL_CHANGES) "Built from $commit, with local changes" else "Built from $commit"
}

/**
 * Whether a newer preview exists, and the way to get it.
 *
 * Home shows an update only when there is one to act on. This is where
 * everything else is said -- up to date, could not check and why -- and where
 * a check can be asked for and automatic ones turned off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdatesPanel(updates: AppUpdates) {
    val context = LocalContext.current
    val state by updates.state.collectAsStateWithLifecycle()
    var automatic by remember { mutableStateOf(updates.checksAutomatically) }

    SettingsPanel {
        Column(Modifier.padding(16.dp)) {
            Text("Updates", style = MaterialTheme.typography.titleSmall)
            Text(
                text = state.describe(),
                style = MaterialTheme.typography.bodySmall,
                color = when (state) {
                    is UpdateState.Failed -> MaterialTheme.colorScheme.error
                    is UpdateState.Available -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp),
            )
            val progress = state as? UpdateState.Downloading
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { if (progress.total > 0) progress.bytes.toFloat() / progress.total else 0f },
                    trackColor = progressTrackColor(),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            val release = when (val current = state) {
                is UpdateState.Available -> current.release
                is UpdateState.Failed -> current.release
                else -> null
            }
            val busy = state is UpdateState.Checking || state is UpdateState.Downloading ||
                state is UpdateState.Installing
            // FlowRow: up to three buttons beside each other is the row that
            // squeezes its last control on a 360 dp phone.
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = updates::check, enabled = !busy) { Text("Check now") }
                if (release != null) {
                    TextButton(onClick = { context.openReleasePage(release) }) { Text("What's new") }
                    (state as? UpdateState.Failed)?.settings?.let { settings ->
                        TextButton(onClick = { context.startActivity(settings) }) { Text("Allow installs") }
                    }
                    Button(onClick = updates::install) {
                        Text(if (state is UpdateState.Failed) "Try again" else "Update")
                    }
                }
            }
        }
        PanelDivider()
        SettingSwitch(
            title = "Check automatically",
            // Said plainly because it is a network request the user did not
            // make: what is asked, how often, and what is sent.
            detail = "Once a day, ask GitHub whether a newer preview has been published. " +
                "Only this build's commit is sent.",
            checked = automatic,
        ) { on ->
            updates.checksAutomatically = on
            automatic = on
        }
    }
}
