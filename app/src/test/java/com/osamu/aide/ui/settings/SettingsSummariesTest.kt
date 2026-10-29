package com.osamu.aide.ui.settings

import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.editor.EditorColorTheme
import com.osamu.aide.editor.EditorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSummariesTest {

    @Test
    fun the_editor_line_names_size_tabs_and_colours() {
        assertEquals(
            "14 sp · 4-space tabs · colours match the app",
            editorSummary(EditorSettings()).text,
        )
        assertEquals(
            "18 sp · 2-space tabs · dark colours",
            editorSummary(EditorSettings(fontSizeSp = 18f, tabWidth = 2, theme = EditorColorTheme.DARK)).text,
        )
    }

    @Test
    fun a_stored_key_is_called_saved_never_ready() {
        // Only a request proves a key works; the hub must not claim more than
        // the store knows.
        val saved = aiSummary(AiProviderType.ANTHROPIC, ready = true)
        assertEquals("Anthropic · key saved", saved.text)
        assertEquals(Tone.NEUTRAL, saved.tone)
        assertFalse(saved.text.contains("ready", ignoreCase = true))

        val missing = aiSummary(AiProviderType.OPENAI, ready = false)
        assertEquals("OpenAI · no key yet", missing.text)
        assertEquals(Tone.ATTENTION, missing.tone)
    }

    @Test
    fun unkeyed_providers_are_described_by_what_makes_them_usable() {
        assertEquals("Custom · no address yet", aiSummary(AiProviderType.CUSTOM, ready = false).text)
        assertEquals("On device · model running", aiSummary(AiProviderType.LOCAL, ready = true).text)
        assertTrue(aiSummary(AiProviderType.LOCAL, ready = false).text.endsWith("not running"))
    }

    @Test
    fun a_missing_git_identity_is_the_hubs_one_problem() {
        val blocked = gitSummary(identityName = null, tokenHosts = 2)
        assertEquals(Tone.PROBLEM, blocked.tone)
        assertEquals("No identity set, so committing is blocked", blocked.text)

        assertEquals("Commits signed as Ada", gitSummary("Ada", 0).text)
        assertEquals("Commits signed as Ada · 1 access token", gitSummary("Ada", 1).text)
        assertEquals("Commits signed as Ada · 3 access tokens", gitSummary("Ada", 3).text)
    }

    @Test
    fun toolchains_say_measuring_until_measured_and_never_zero_megabytes() {
        assertEquals("Measuring…", toolchainSummary(null, 0).text)
        assertEquals("Nothing downloaded yet", toolchainSummary(0L, 0).text)
        assertEquals("less than 1 MB · 1 download", toolchainSummary(1_000L, 1).text)
        assertEquals("551 MB · 2 downloads", toolchainSummary(551L * 1024 * 1024, 2).text)
        assertEquals("1.3 GB · 9 downloads", toolchainSummary(1342L * 1024 * 1024, 9).text)
    }

    @Test
    fun only_states_that_stop_something_are_coloured() {
        assertEquals(Tone.NEUTRAL, signingSummary(false).tone)
        assertEquals(Tone.NEUTRAL, signingSummary(true).tone)
        assertEquals(Tone.NEUTRAL, toolchainSummary(0L, 0).tone)
        assertEquals(Tone.NEUTRAL, editorSummary(EditorSettings()).tone)
    }

    @Test
    fun about_shows_the_version_when_there_is_one() {
        assertEquals("Version 1.2.0 · GPLv3", aboutSummary("1.2.0").text)
        assertEquals("GPLv3 · licences and credits", aboutSummary("").text)
    }
}
