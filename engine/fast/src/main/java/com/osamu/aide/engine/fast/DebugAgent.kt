package com.osamu.aide.engine.fast

import com.osamu.aide.engine.api.DebugAgentSource
import com.osamu.aide.engine.api.DebuggerRequest
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Makes a built app debuggable *by us*, by putting a JDWP listener inside it.
 *
 * Spike R15 established that an unprivileged app cannot attach to another
 * app's JDWP -- but that ART ships OpenJDK's `libjdwp.so`, and any debuggable
 * process may attach it to *itself* and listen on a socket. So the debuggee
 * half of the debugger is not a permission AIDE-OS asks for; it is four lines
 * that this puts into the app it builds. `tools/jdwp/FINDINGS.md`.
 *
 * Two things go into the APK, and both are reverted by simply not asking:
 *
 *  - **A generated `ContentProvider`**, because providers are created before
 *    `Application.onCreate` and before any activity, so the port is open by the
 *    time there is anything to stop. The alternative -- generating an
 *    `Application` subclass -- would silently replace the user's own, and a
 *    project that has one is exactly the project complex enough to be worth
 *    debugging. `androidx.startup` uses this trick for the same reason; see
 *    [ManifestMerger].
 *  - **`android.permission.INTERNET`**, because `dt_socket` is the only
 *    transport ART's agent has, so the JDWP server is a TCP listener. Without
 *    it the agent calls `exit(2)` from a stdout nobody reads and the app
 *    vanishes at startup with no exception and no tombstone.
 *
 * **Never in a release build, and never unasked.** This adds a permission the
 * user did not write and opens a port on their app; doing it to every debug
 * build because it is convenient would be a decision taken on their behalf.
 * [BuildRequest.debugger] is null unless something asked, and a release build
 * refuses it outright.
 */
internal object DebugAgent {

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    /**
     * Writes the agent's source into [workspace] and its declarations into
     * [manifest].
     *
     * Returns the generated file, which the caller does not need to add to the
     * compiler's inputs: it is written into `generated/java`, which
     * [BuildWorkspace.generatedJavaSources] already sweeps for `R.java`.
     */
    fun install(
        workspace: BuildWorkspace,
        manifest: File,
        applicationId: String,
        request: DebuggerRequest,
    ): File {
        val source = File(workspace.generatedJava, DebugAgentSource.relativePath)
        source.parentFile?.mkdirs()
        source.writeText(DebugAgentSource.javaSource(request))
        declare(manifest, applicationId, request.handshakeAuthority)
        return source
    }

    /**
     * Adds the provider, the permission and the IDE query to an already-merged
     * manifest.
     *
     * Idempotent by name, because a rebuild reuses the workspace and a second
     * `<provider>` with the same authority makes the *install* fail rather than
     * the build.
     *
     * **The `<queries>` entry is not optional on API 30 and up.** Package
     * visibility hides the IDE from an app that does not declare it wants to
     * see it, and `ContentResolver.call` against an invisible provider does not
     * fail loudly -- it throws "Unknown authority", which the agent catches as
     * "nobody is coming", and startup breakpoints silently stop working.
     */
    private fun declare(manifest: File, applicationId: String, handshakeAuthority: String?) {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest)
        val root = document.documentElement

        if (!root.hasPermission(DebugAgentSource.INTERNET)) {
            root.insertBefore(
                document.createElement("uses-permission").apply {
                    setAttributeNS(ANDROID_NAMESPACE, "android:name", DebugAgentSource.INTERNET)
                },
                root.firstChild,
            )
        }

        if (handshakeAuthority != null && root.childElements().none { queries ->
                queries.tagName == "queries" && queries.childElements().any {
                    it.tagName == "provider" &&
                        it.getAttributeNS(ANDROID_NAMESPACE, "authorities") == handshakeAuthority
                }
            }
        ) {
            val queries = document.createElement("queries")
            queries.appendChild(
                document.createElement("provider").apply {
                    setAttributeNS(ANDROID_NAMESPACE, "android:authorities", handshakeAuthority)
                },
            )
            root.insertBefore(queries, root.firstChild)
        }

        val application = document.getElementsByTagName("application").item(0) as? Element
            ?: throw IllegalStateException(
                "this project's manifest has no <application>, so there is nowhere " +
                    "to put the debugger",
            )
        val name = DebuggerRequest.AGENT_CLASS
        if (application.childElements().none {
                it.tagName == "provider" &&
                    it.getAttributeNS(ANDROID_NAMESPACE, "name") == name
            }
        ) {
            application.appendChild(
                document.createElement("provider").apply {
                    setAttributeNS(ANDROID_NAMESPACE, "android:name", name)
                    // Namespaced by applicationId: an authority is unique
                    // across the whole device, and two apps built here with the
                    // same literal would refuse to install alongside each other.
                    setAttributeNS(
                        ANDROID_NAMESPACE,
                        "android:authorities",
                        DebuggerRequest.agentAuthority(applicationId),
                    )
                    // Exported so the IDE can hold a reference to it, which is
                    // what keeps this app from being frozen while the IDE is in
                    // front. Every operation returns nothing, so reaching it
                    // gains a caller nothing else.
                    setAttributeNS(ANDROID_NAMESPACE, "android:exported", "true")
                },
            )
        }

        TransformerFactory.newInstance().newTransformer()
            .transform(DOMSource(document), StreamResult(manifest))
    }

    private fun Element.hasPermission(permission: String): Boolean =
        childElements().any {
            it.tagName == "uses-permission" &&
                it.getAttributeNS(ANDROID_NAMESPACE, "name") == permission
        }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
}
