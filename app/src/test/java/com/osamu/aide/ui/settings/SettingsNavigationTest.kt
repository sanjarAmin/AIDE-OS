package com.osamu.aide.ui.settings

import com.osamu.aide.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsNavigationTest {

    @Test
    fun category_from_route_key_resolves_correctly() {
        assertEquals(SettingsCategory.EDITOR, SettingsCategory.fromRouteKey("editor"))
        assertEquals(SettingsCategory.AI, SettingsCategory.fromRouteKey("ai"))
        assertEquals(SettingsCategory.GIT, SettingsCategory.fromRouteKey("git"))
        assertEquals(SettingsCategory.TOOLCHAINS, SettingsCategory.fromRouteKey("toolchains"))
        assertEquals(SettingsCategory.SIGNING, SettingsCategory.fromRouteKey("signing"))
        assertEquals(SettingsCategory.ABOUT, SettingsCategory.fromRouteKey("about"))
    }

    @Test
    fun category_from_route_key_is_case_insensitive() {
        assertEquals(SettingsCategory.AI, SettingsCategory.fromRouteKey("AI"))
        assertEquals(SettingsCategory.EDITOR, SettingsCategory.fromRouteKey("Editor"))
        assertEquals(SettingsCategory.TOOLCHAINS, SettingsCategory.fromRouteKey("TOOLCHAINS"))
    }

    @Test
    fun category_from_route_key_handles_invalid_and_null() {
        assertNull(SettingsCategory.fromRouteKey(null))
        assertNull(SettingsCategory.fromRouteKey(""))
        assertNull(SettingsCategory.fromRouteKey("   "))
        assertNull(SettingsCategory.fromRouteKey("nonexistent"))
    }

    @Test
    fun routes_settings_generates_correct_urls() {
        assertEquals("settings", Routes.settings())
        assertEquals("settings", Routes.settings(null))
        assertEquals("settings", Routes.settings(""))
        assertEquals("settings?category=ai", Routes.settings("ai"))
        assertEquals("settings?category=git", Routes.settings("git"))
    }

    @Test
    fun all_categories_have_unique_route_keys_and_titles() {
        val categories = SettingsCategory.entries
        val routeKeys = categories.map { it.routeKey }
        val titles = categories.map { it.title }

        assertEquals(categories.size, routeKeys.distinct().size)
        assertEquals(categories.size, titles.distinct().size)

        categories.forEach { category ->
            assertNotNull(category.icon)
            assertNotNull(category.subtitle)
            assertNotNull(category.shortTitle)
        }
    }
}
