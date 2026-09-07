package com.osamu.aide.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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
fun AboutSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember(context) { installedVersion(context) }

    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("About", style = MaterialTheme.typography.titleMedium)
        Text(
            // Built rather than interpolated: an absent version would leave
            // "AIDE-OS  — an on-device IDE", two spaces and a dash hanging.
            text = buildString {
                append("AIDE-OS")
                if (version.isNotEmpty()) append(' ').append(version)
                append(" — an on-device IDE for phones and tablets. It builds real ")
                append("Android apps with no root, no Termux and no Linux userland.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Text(
            text = "Licence",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            // The exact words matter: this is the notice GPLv3 §4 asks for, and
            // paraphrasing a warranty disclaimer is how it stops being one.
            text = "GNU General Public License, version 3 or later. This program comes " +
                "with ABSOLUTELY NO WARRANTY. It is free software, and you are welcome " +
                "to redistribute it under the terms of that licence — the full text " +
                "ships as LICENSE in the source repository.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "Built on other people's work",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = "The editor widget is sora-editor (LGPL-2.1), used unmodified. The " +
                "terminal is Termux's emulator (Apache-2.0), vendored byte-identical. " +
                "aapt2 comes from AOSP and ECJ from Eclipse; the compilers and " +
                "runtimes this app downloads — Kotlin, clang, OpenJDK, Node, Mono — " +
                "keep their own licences. NOTICE.md in the source repository lists " +
                "every one and where that licence is recorded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The version of this install, or nothing at all.
 *
 * `getPackageInfo` can throw for a package being replaced, and a version is not
 * worth an exception on a settings screen -- so the name simply loses its
 * suffix rather than the section losing its heading.
 */
private fun installedVersion(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()
