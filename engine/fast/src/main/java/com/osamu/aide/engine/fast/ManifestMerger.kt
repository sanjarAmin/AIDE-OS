package com.osamu.aide.engine.fast

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Merges the manifests an AAR contributes into the project's own.
 *
 * **Without this a library's components silently do not exist.** `AarExtractor`
 * has always unpacked each `AndroidManifest.xml`; nothing read them, so aapt2
 * linked the app's manifest alone and every `<provider>`, `<receiver>`,
 * `<service>` and `<uses-permission>` an AndroidX library declares was absent
 * from the built APK. The build succeeded, the APK installed, and the library
 * behaved as though it had never been initialised.
 *
 * `androidx.startup` is the case that makes it matter rather than a curiosity:
 * it ships a `<provider>` whose entire purpose is to run other libraries'
 * initialisers at install time, and `emoji2` -- which Compose pulls in -- relies
 * on it. A missing provider there is not a crash. It is text that renders
 * slightly wrong, forever, with nothing in the build to say why.
 *
 * ## What this does and does not do
 *
 * This is **not** AGP's manifest merger, and the difference is worth being
 * explicit about rather than discovering later. AGP implements a specification
 * with node markers, selectors, priorities and a full `tools:` namespace. This
 * implements the part every ordinary Android build depends on:
 *
 * - `<uses-permission>` and `<uses-feature>` are unioned by name;
 * - the `<application>` element's children -- `<provider>`, `<receiver>`,
 *   `<service>`, `<activity>`, `<meta-data>` and the rest -- are added if the
 *   project does not already declare one with the same `android:name`;
 * - `${applicationId}` is substituted, because a library authority is written
 *   as `${applicationId}.androidx-startup` and an unsubstituted one installs as
 *   a literal and collides with every other app that did the same.
 *
 * **The project always wins.** A component the project declares is never
 * replaced, which is the one merge rule a user can reason about without reading
 * a specification.
 *
 * Of the `tools:` namespace, only `tools:node="remove"` is honoured. The other
 * markers describe how to combine an element with a same-named one, and the
 * project already wins that comparison here. They are stripped from the output
 * rather than passed through, because aapt2 refuses an unbound prefix -- and
 * because leaving them in would let a marker this does not implement look as
 * though it had been. `FINDINGS.md` section 13.
 */
internal object ManifestMerger {

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"
    private const val NAME = "name"
    private const val APPLICATION = "application"
    private const val PLACEHOLDER = "\${applicationId}"

    /**
     * Elements merged by union at the manifest's top level.
     *
     * Deliberately a list rather than "everything that is not `<application>`".
     * `<uses-sdk>`, `<queries>` and `<permission>` all have merge rules of their
     * own -- lowest wins, union with dedup by a different key, outright
     * conflict -- and quietly unioning them would produce a manifest that
     * differs from AGP's in ways nothing here would catch.
     */
    private val UNIONED = setOf("uses-permission", "uses-feature")

    /**
     * Merges [libraries] into [projectManifest], writing the result to [output].
     *
     * Libraries are applied in order, so an earlier one is overridden by a
     * later one where both declare the same component -- matching the overlay
     * order `ResourceStage` uses for resources, so the two halves of a build
     * do not disagree about which library came first.
     *
     * Returns [output] on success. On any failure the **project's own manifest
     * is returned unchanged**: a build that links the app's manifest alone is
     * what this project did until now and still produces a working APK, which
     * is a better outcome than failing a build over a library's malformed XML.
     */
    fun merge(
        projectManifest: File,
        libraries: List<File>,
        applicationId: String,
        output: File,
    ): File {
        if (libraries.none { it.isFile }) return projectManifest

        return runCatching {
            val builder = DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
            fun parse(file: File): Document = builder.parse(file)

            val merged = parse(projectManifest)
            val root = merged.documentElement
            val application = root.childElements().firstOrNull { it.tagName == APPLICATION }

            val declaredTop = root.childElements()
                .filter { it.tagName in UNIONED }
                .mapNotNullTo(mutableSetOf()) { it.key() }
            val declaredComponents = application?.childElements()
                ?.mapNotNullTo(mutableSetOf()) { it.key() }
                ?: mutableSetOf()

            libraries.filter { it.isFile }.forEach { library ->
                val source = runCatching { parse(library) }.getOrNull()
                    ?: return@forEach

                source.documentElement.childElements().forEach { element ->
                    when {
                        element.tagName in UNIONED -> {
                            val key = element.key() ?: return@forEach
                            if (declaredTop.add(key)) {
                                root.appendImported(merged, element, applicationId)
                            }
                        }

                        element.tagName == APPLICATION && application != null -> {
                            element.childElements().forEach { component ->
                                val key = component.key() ?: return@forEach
                                if (declaredComponents.add(key)) {
                                    application.appendImported(merged, component, applicationId)
                                }
                            }
                        }
                    }
                }
            }

            output.parentFile?.mkdirs()
            merged.writeTo(output)
            output
        }.getOrDefault(projectManifest)
    }

    /**
     * `tagName` plus `android:name`, which is how Android identifies a
     * component. Null for an element with no name, which cannot be compared to
     * anything and is left alone.
     */
    private fun Element.key(): String? {
        val name = getAttributeNS(ANDROID_NAMESPACE, NAME).takeIf { it.isNotBlank() }
            ?: return null
        return "$tagName#$name"
    }

    /**
     * Copies [element] into [document] under this node, substituting
     * `${applicationId}`.
     *
     * Elements carrying a `tools:` attribute are dropped rather than copied.
     * aapt2 rejects an unbound prefix, and binding the namespace would let a
     * marker this does not implement look as though it had been honoured.
     */
    private fun Node.appendImported(document: Document, element: Element, applicationId: String) {
        if (element.isRemoved()) return
        val imported = document.importNode(element, true) as Element
        imported.substitute(applicationId)
        imported.stripTools()
        appendChild(imported)
    }

    /**
     * Whether the library asked for this element to be taken out.
     *
     * The one `tools:node` value honoured. Everything else -- `merge`,
     * `replace`, `mergeOnlyAttributes` -- describes how to combine an element
     * with a same-named one, and the project already wins that comparison here,
     * so treating them as an ordinary merge is the same answer by a shorter
     * route.
     *
     * **`merge` in particular must not be treated as a marker to skip.** It is
     * what `androidx.startup` puts on its `<provider>`, and dropping every
     * element carrying a `tools:` attribute -- the first version of this --
     * threw away the exact component the merger exists to bring in, while the
     * build reported success.
     */
    private fun Element.isRemoved(): Boolean =
        getAttributeNS(TOOLS_NAMESPACE, "node") in setOf("remove", "removeAll")

    /**
     * Removes every `tools:` attribute from a copied element.
     *
     * They are build-time instructions, not manifest content, and aapt2 refuses
     * an unbound prefix. Stripping them rather than binding the namespace keeps
     * a marker this does not implement from looking as though it had been
     * honoured.
     */
    private fun Element.stripTools() {
        val attributes = attributes
        // Backwards: removing shifts every later index down by one.
        for (index in attributes.length - 1 downTo 0) {
            val attribute = attributes.item(index)
            if (attribute.namespaceURI == TOOLS_NAMESPACE) {
                removeAttributeNS(TOOLS_NAMESPACE, attribute.localName)
            }
        }
        childElements().forEach { it.stripTools() }
    }

    private fun Element.substitute(applicationId: String) {
        val attributes = attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (PLACEHOLDER in attribute.nodeValue.orEmpty()) {
                attribute.nodeValue = attribute.nodeValue.replace(PLACEHOLDER, applicationId)
            }
        }
        childElements().forEach { it.substitute(applicationId) }
    }

    private fun Node.childElements(): List<Element> {
        val children = childNodes
        return (0 until children.length)
            .mapNotNull { children.item(it) as? Element }
    }

    private fun Document.writeTo(file: File) {
        TransformerFactory.newInstance().newTransformer().transform(
            DOMSource(this),
            StreamResult(file),
        )
    }
}
