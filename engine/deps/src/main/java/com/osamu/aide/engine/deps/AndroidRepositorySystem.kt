package com.osamu.aide.engine.deps

import org.apache.maven.model.Model
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.ModelBuilder
import org.apache.maven.model.building.ModelBuildingRequest
import org.apache.maven.model.building.ModelProblemCollector
import org.apache.maven.model.validation.ModelValidator
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.spi.connector.transport.GetTask
import org.eclipse.aether.spi.connector.transport.PeekTask
import org.eclipse.aether.spi.connector.transport.PutTask
import org.eclipse.aether.spi.connector.transport.Transporter
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.transfer.NoTransporterException
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.util.graph.transformer.ConflictResolver
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Maven's resolver, wired so that it starts on Android.
 *
 * One class stops it: `DefaultModelValidator` compiles two patterns in its
 * static initialiser,
 *
 *     \$\{(.+?)}        and        \$\{(project.+?)}
 *
 * whose closing brace is unescaped. The JDK's regex engine reads a lone `}` as
 * a literal; **Android's does not, because Android's is ICU**, which rejects
 * it outright. The throw happens in `<clinit>`, so the class is poisoned for
 * the life of the process and every later touch is a `NoClassDefFoundError`
 * rather than the original `PatternSyntaxException` -- which is why the second
 * and third tests in this spike report a different error from the first.
 *
 * Present unchanged in every maven-model-builder from 3.8.8 to 3.9.16, so
 * there is no version to bump to. It is also the *only* occurrence across every
 * Maven jar the resolver pulls in, which is what makes this worth working
 * around rather than abandoning.
 *
 * The way around it is small because Maven left the seams open: the validator
 * is a two-method interface, `DefaultModelBuilderFactory.newModelValidator()`
 * is protected, and so is `RepositorySystemSupplier.getModelBuilder()`. Supply
 * a validator that is never `DefaultModelValidator` and the class is never
 * loaded.
 */
internal class AndroidRepositorySystemSupplier : RepositorySystemSupplier() {

    override fun getModelBuilder(): ModelBuilder =
        object : DefaultModelBuilderFactory() {
            override fun newModelValidator(): ModelValidator = PermissiveModelValidator
        }.newInstance()

    /**
     * Replaces Apache HttpClient wholesale.
     *
     * The bundled transport cannot work on Android and cannot be made to: the
     * platform ships its own ancient, stripped `org.apache.http` on the **boot
     * classpath**, which by definition wins over anything in the APK. So
     * `SSLConnectionSocketFactory` links against the platform's
     * `AllowAllHostnameVerifier`, finds it has no `INSTANCE` field, and dies in
     * its static initialiser. Shipping httpclient 4.5.14 does not help; the app
     * copy is never the one that loads.
     *
     * The usual escape is to shade `org.apache.http` into another package. The
     * cheaper one is this: [Transporter] is five methods over what is, for a
     * Maven repository, plain HTTP GET of static files. `HttpURLConnection` is
     * on every Android and answers the whole SPI.
     */
    override fun getTransporterFactories(
        checksumExtractors: MutableMap<String, org.eclipse.aether.transport.http.ChecksumExtractor>,
    ): MutableMap<String, TransporterFactory> = linkedMapOf(
        "file" to FileTransporterFactory(),
        "http" to UrlConnectionTransporterFactory(),
    )
}

/** Serves `http` and `https`; a Maven repository needs nothing else. */
private class UrlConnectionTransporterFactory : TransporterFactory {
    override fun getPriority(): Float = 10f

    override fun newInstance(
        session: RepositorySystemSession,
        repository: RemoteRepository,
    ): Transporter {
        val protocol = repository.url.substringBefore(":").lowercase()
        if (protocol != "http" && protocol != "https") {
            throw NoTransporterException(repository, "unsupported protocol: $protocol")
        }
        return UrlConnectionTransporter(repository)
    }
}

private class UrlConnectionTransporter(private val repository: RemoteRepository) : Transporter {

    /**
     * A 404 has to be told apart from a real failure, or the resolver treats a
     * repository that simply does not carry an artifact as a broken one and
     * stops instead of trying the next.
     */
    override fun classify(error: Throwable): Int =
        if (error is FileNotFoundException) Transporter.ERROR_NOT_FOUND else Transporter.ERROR_OTHER

    override fun peek(task: PeekTask) {
        open(task.location.toString(), head = true).also { it.disconnect() }
    }

    override fun get(task: GetTask) {
        val connection = open(task.location.toString(), head = false)
        try {
            connection.inputStream.use { input ->
                task.newOutputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Publishing to a repository is not something an IDE on a phone does. */
    override fun put(task: PutTask): Unit = throw UnsupportedOperationException("read-only")

    override fun close() = Unit

    private fun open(relative: String, head: Boolean): HttpURLConnection {
        val base = repository.url.trimEnd('/')
        val url = URL("$base/${relative.trimStart('/')}")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = if (head) "HEAD" else "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AIDE-OS")
            // getInputStream throws FileNotFoundException on 404, which is what
            // classify() reads; ask for the code first so a HEAD does too.
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                disconnect()
                throw FileNotFoundException(url.toString())
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
    }
}

/**
 * A validator that accepts everything.
 *
 * Defensible for this use and only this use. Validation exists to tell an
 * author their own POM is wrong; we are reading *published* artifacts from
 * Maven Central and Google Maven, which were validated by the tooling that
 * published them. Nothing here is being authored, so there is no author to
 * tell.
 *
 * What it costs: a genuinely malformed POM in a repository will be read as far
 * as it parses rather than rejected with a diagnostic. That surfaces later as a
 * missing dependency, which is worse to debug -- so if `:engine:deps` ever
 * resolves POMs a user wrote, this is the first thing to revisit.
 */
private object PermissiveModelValidator : ModelValidator {
    override fun validateRawModel(
        model: Model,
        request: ModelBuildingRequest,
        problems: ModelProblemCollector,
    ) = Unit

    override fun validateEffectiveModel(
        model: Model,
        request: ModelBuildingRequest,
        problems: ModelProblemCollector,
    ) = Unit
}

/**
 * Picks the highest version in a conflict, the way Gradle does.
 *
 * Maven's default is *nearest-wins*: the version closest to the root of the
 * graph. AndroidX is not built for that. `appcompat:1.7.0` declares a **hard
 * range** on `appcompat-resources:[1.7.0]`, and when a soft version for the
 * same artifact arrives by another path Maven has a hard requirement and a
 * nearer soft one and refuses to choose -- `UnsolvableVersionConflictException`,
 * and resolution stops. Every real AndroidX graph hits this.
 *
 * Gradle picks the highest version and every Android project on earth is
 * written expecting that, so this is the semantics to match. It is not merely a
 * workaround for the exception: nearest-wins would silently select *older*
 * AndroidX artifacts than the build the user gets from Gradle, which is a worse
 * outcome than failing.
 *
 * maven-resolver 1.9 ships only `NearestVersionSelector`, so this is written
 * rather than configured.
 */
internal class HighestVersionSelector : ConflictResolver.VersionSelector() {

    override fun selectVersion(context: ConflictResolver.ConflictContext) {
        var winner: ConflictResolver.ConflictItem? = null
        for (item in context.items) {
            if (!isAcceptable(context, item)) continue
            if (winner == null || compare(item, winner) > 0) winner = item
        }
        context.winner = winner ?: context.items.firstOrNull()
    }

    /** A node the graph has already excluded cannot be the winner. */
    private fun isAcceptable(
        context: ConflictResolver.ConflictContext,
        item: ConflictResolver.ConflictItem,
    ): Boolean = context.isIncluded(item.node)

    private fun compare(
        candidate: ConflictResolver.ConflictItem,
        incumbent: ConflictResolver.ConflictItem,
    ): Int {
        val a = candidate.node.version
        val b = incumbent.node.version
        return when {
            a == null && b == null -> 0
            a == null -> -1
            b == null -> 1
            else -> a.compareTo(b)
        }
    }
}
