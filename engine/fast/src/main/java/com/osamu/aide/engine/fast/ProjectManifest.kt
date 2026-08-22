package com.osamu.aide.engine.fast

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The few facts the build engine needs out of a project's `AndroidManifest.xml`.
 *
 * Read with DOM rather than Android's pull parser so the same code runs in a JVM
 * unit test. The manifest is tens of lines; the cost of holding it in memory is
 * not worth a streaming parser and an instrumentation test to cover it.
 */
internal object ProjectManifest {

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /**
     * Matches `:app`'s own floor, and the platform's oldest still-supported API.
     * Used when the manifest omits `uses-sdk` or is unreadable -- a malformed
     * manifest fails at aapt2, which reports it far better than this could.
     */
    const val DEFAULT_MIN_SDK = 26

    /**
     * The `minSdkVersion` D8 desugars against.
     *
     * Getting this wrong is silent and expensive in one direction: too high and
     * D8 leaves language features in the dex that the device's runtime cannot
     * execute, so the APK builds cleanly and crashes on launch on exactly the
     * old devices the developer declared support for. Too low only costs a
     * little dex size, so that is the way this rounds when it cannot tell.
     *
     * A codename ("TIRAMISU", "S") is treated as unreadable rather than mapped:
     * the table would need updating every release, and being wrong here is the
     * failure above.
     */
    fun minSdk(manifest: File): Int {
        if (!manifest.isFile) return DEFAULT_MIN_SDK

        val declared = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val document = manifest.inputStream().use { factory.newDocumentBuilder().parse(it) }
            document.getElementsByTagName("uses-sdk")
                .takeIf { it.length > 0 }
                ?.item(0)
                ?.let { it as? Element }
                ?.getAttributeNS(ANDROID_NS, "minSdkVersion")
        }.getOrNull()

        return declared?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_MIN_SDK
    }
}
