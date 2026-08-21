package com.osamu.aide.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Guards against partially-overridden colour schemes.
 *
 * Material3's `lightColorScheme()`/`darkColorScheme()` fill every role the
 * caller does not name with the baseline purple palette. Naming `primary` but
 * not `primaryContainer` therefore produces a purple FAB in an otherwise blue
 * app -- a bug no other test would notice, because nothing throws and the
 * colour is perfectly valid.
 *
 * Rather than diffing against the defaults (which false-positives wherever our
 * intended value happens to match, such as white-on-primary), this asserts the
 * stronger property: every role must hold a colour we actually declared.
 */
class AideColorSchemeTest {

    /**
     * `Color` is a value class, so its getters are name-mangled and erased to
     * `long`. Matching on that shape is what lets both the palette and the
     * scheme be enumerated without listing anything by hand -- which is the
     * point, since the roles we forget are exactly the ones that break.
     */
    private fun longGetters(type: Class<*>, staticOnly: Boolean): List<Method> =
        type.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 }
            .filter { it.returnType == Long::class.javaPrimitiveType }
            .filter { Modifier.isStatic(it.modifiers) == staticOnly }
            .sortedBy { it.name }

    private fun roleName(getter: Method) = getter.name.removePrefix("get").substringBefore('-')

    /** Every colour token declared in Color.kt, plus the two neutrals. */
    private val palette: Set<Long> by lazy {
        val tokensClass = Class.forName("com.osamu.aide.core.ui.theme.ColorKt")
        longGetters(tokensClass, staticOnly = true)
            .map { it.invoke(null) as Long }
            .toSet() + setOf(Color.White.value.toLong(), Color.Black.value.toLong())
    }

    private fun rolesOutsidePalette(scheme: ColorScheme): List<String> =
        longGetters(ColorScheme::class.java, staticOnly = false)
            .mapNotNull { getter ->
                roleName(getter).takeIf { (getter.invoke(scheme) as Long) !in palette }
            }

    @Test
    fun `every dark scheme role uses a declared AIDE colour`() {
        val strays = rolesOutsidePalette(AideDarkColors)
        assertTrue(
            "dark roles not drawn from the AIDE palette (likely Material's default purple): $strays",
            strays.isEmpty(),
        )
    }

    @Test
    fun `every light scheme role uses a declared AIDE colour`() {
        val strays = rolesOutsidePalette(AideLightColors)
        assertTrue(
            "light roles not drawn from the AIDE palette (likely Material's default purple): $strays",
            strays.isEmpty(),
        )
    }

    @Test
    fun `the reflection actually enumerated roles and tokens`() {
        // If Material3 or Kotlin changes how value-class properties compile,
        // the filters above could match nothing and every test would pass
        // vacuously.
        assertTrue(
            "no colour roles discovered on ColorScheme",
            longGetters(ColorScheme::class.java, staticOnly = false).size > 30,
        )
        assertTrue("no palette tokens discovered in Color.kt", palette.size > 20)
    }
}
