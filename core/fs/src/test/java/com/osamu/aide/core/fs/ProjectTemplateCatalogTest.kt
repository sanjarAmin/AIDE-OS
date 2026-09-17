package com.osamu.aide.core.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Every template in the catalog, held to what being in the catalog claims.
 *
 * [ProjectTemplateTest] pins the behaviour of the default starter for each
 * language in detail. This one is broad instead: it writes *all* of them and
 * asserts the properties that make a template usable at all, so that adding a
 * twelfth cannot ship a picker entry that writes nothing, writes an Android
 * skeleton for a Node project, or names a JNI symbol no Activity will find.
 *
 * The distinction matters because the failure mode of a bad template is not a
 * crash. It is a project that is created successfully and then does not build,
 * some minutes later, with an error about the user's own code.
 */
class ProjectTemplateCatalogTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun projectFor(
        template: ProjectTemplate,
        applicationId: String = "com.example.demo",
    ) = Project(
        name = "Demo ${template.id}",
        rootDir = temporaryFolder.newFolder(template.id),
        applicationId = applicationId,
        language = template.language,
        engine = BuildEngine.FAST,
        lastOpenedAt = 0L,
        dependencies = template.dependencies,
    )

    /**
     * **The catalog has no holes, whatever was touched first.**
     *
     * `ALL` used to be a `val` in `ProjectTemplate`'s companion, which made it
     * a static field of the sealed class -- built by that class's initialiser,
     * which runs *before* any subclass object's. So touching a template
     * directly initialised the superclass, which built the list out of objects
     * that did not exist yet and put **nulls** in it. The symptom is an NPE on
     * `getLanguage()` for a list whose type says it cannot contain null.
     *
     * It is entirely order-dependent: the same two tests passed in one order
     * and crashed in the other when JUnit reordered them. This test touches a
     * template *first*, on purpose, before asking for the list.
     */
    @Test
    fun `touching a template first does not leave holes in the catalog`() {
        // Deliberately the first thing this test does, and deliberately not
        // the first entry of ALL: initialising a template must not be what
        // builds the catalog.
        val touchedFirst: ProjectTemplate = MonoCollectionsApp
        assertEquals(SourceLanguage.CSHARP, touchedFirst.language)

        @Suppress("SENSELESS_COMPARISON")
        val holes = ProjectTemplate.ALL.count { it == null }
        assertEquals(
            "ProjectTemplate.ALL was built during a subclass's initialisation, " +
                "so it holds nulls; the catalog must not be a field of the sealed class",
            0,
            holes,
        )
        assertTrue("the catalog is empty", ProjectTemplate.ALL.isNotEmpty())
    }

    @Test
    fun `every template has a distinct id and says what it is for`() {
        val ids = ProjectTemplate.ALL.map { it.id }
        assertEquals("two templates share an id: $ids", ids.size, ids.distinct().size)

        ProjectTemplate.ALL.forEach { template ->
            assertTrue("${template.id} has no display name", template.displayName.isNotBlank())
            // The objective is what the picker shows under the name, and a
            // template that cannot finish "pick this one to ..." has not
            // earned a row of its own next to one that can.
            assertTrue("${template.id} has no objective", template.objective.isNotBlank())
        }
    }

    /**
     * A template that writes nothing creates a project with a descriptor and no
     * sources, which is the one state neither the editor nor the engine can do
     * anything with -- and `createProject` reports success for it.
     */
    @Test
    fun `every template writes something`() {
        ProjectTemplate.ALL.forEach { template ->
            val project = projectFor(template)
            template.write(project)

            val written = project.rootDir.walkTopDown().filter { it.isFile }.toList()
            assertTrue("${template.id} wrote no files", written.isNotEmpty())
        }
    }

    /**
     * An Android template is buildable the moment it is created, and a run-only
     * one never claims to be.
     *
     * `isBuildable` is the gate `WorkspaceViewModel` consults before handing a
     * project to the aapt2/ECJ/D8 chain. Both directions are wrong in their own
     * way: an Android template answering false cannot be built at all, and a
     * Node template answering true is sent into a pipeline that will fail on a
     * missing manifest rather than saying this is not that kind of project.
     */
    @Test
    fun `android templates are buildable and run-only templates are not`() {
        ProjectTemplate.ALL.forEach { template ->
            val project = projectFor(template)
            template.write(project)
            val layout = ProjectLayout.of(project)

            if (template.language in RUN_ONLY) {
                assertTrue(
                    "${template.id} reported itself buildable as an APK",
                    !layout.isBuildable(),
                )
                assertTrue(
                    "${template.id} wrote an Android resource directory",
                    !layout.resourceDir.isDirectory,
                )
                assertTrue(
                    "${template.id} has no .gitignore for what a run leaves behind",
                    File(project.rootDir, ".gitignore").isFile,
                )
            } else {
                assertTrue("${template.id} is not buildable", layout.isBuildable())
                assertTrue(
                    "${template.id} does not declare its package in the manifest",
                    layout.manifestFile.readText().contains("package=\"com.example.demo\""),
                )
            }
        }
    }

    /**
     * Every Android template reads a string resource from code.
     *
     * The one line that makes a template a real test of the build engine: it
     * only compiles if aapt2 linked the resources, generated `R.java`, and the
     * compiler was handed it. A template with no resource reference would still
     * build with half the pipeline broken, and would then be the template
     * someone reaches for when debugging the other half.
     */
    @Test
    fun `every android template reads a string resource it declares`() {
        ProjectTemplate.ALL.filter { it.language !in RUN_ONLY }.forEach { template ->
            val project = projectFor(template)
            template.write(project)
            val layout = ProjectLayout.of(project)

            val sources = (layout.javaSources() + layout.kotlinSources())
                .joinToString("\n") { it.readText() }
            val referenced = Regex("R\\.string\\.(\\w+)").findAll(sources)
                .map { it.groupValues[1] }
                .toSet()
            assertTrue("${template.id} never reads a string resource", referenced.isNotEmpty())

            val declared = Regex("<string name=\"(\\w+)\">")
                .findAll(File(layout.resourceDir, "values/strings.xml").readText())
                .map { it.groupValues[1] }
                .toSet()
            assertEquals(
                "${template.id} reads string resources it does not declare",
                emptySet<String>(),
                referenced - declared,
            )
        }
    }

    /**
     * A native template's JNI symbol matches the Activity that declares it.
     *
     * **This is the assertion worth having.** The C symbol has to spell
     * `Java_<package>_<class>_<method>` with every `.` replaced by `_`, and the
     * package comes from the project's application id -- so a template that
     * hardcoded a package would compile, link, package and install, and then
     * die with `UnsatisfiedLinkError` at the first call. Nothing earlier
     * notices: the library loaded fine, and only the method was never found.
     *
     * Checked against an application id that is *not* the default, because a
     * hardcoded `com.example.demo` would pass every other test here.
     */
    @Test
    fun `native templates name their JNI symbols after the project's package`() {
        val native = ProjectTemplate.ALL.filter { it.language in setOf(SourceLanguage.C, SourceLanguage.CPP) }
        assertTrue("no native templates to check", native.isNotEmpty())

        native.forEach { template ->
            val project = projectFor(template, applicationId = "org.other.thing")
            template.write(project)
            val layout = ProjectLayout.of(project)

            val sources = layout.nativeSources()
            assertTrue("${template.id} wrote no native sources", sources.isNotEmpty())

            val declared = Regex("private native \\w+ (\\w+)\\(")
                .findAll(layout.javaSources().joinToString("\n") { it.readText() })
                .map { it.groupValues[1] }
                .toList()
            assertTrue("${template.id} declares no native method", declared.isNotEmpty())

            val nativeText = sources.joinToString("\n") { it.readText() }
            declared.forEach { method ->
                val symbol = "Java_org_other_thing_MainActivity_$method"
                assertTrue(
                    "${template.id} does not implement $symbol; the app would load the " +
                        "library and then fail with UnsatisfiedLinkError at the call",
                    nativeText.contains(symbol),
                )
            }

            // And the Java side loads a library named the way the build names
            // it -- after the project, not after the source file.
            assertTrue(
                "${template.id} does not load a native library",
                layout.javaSources().any { "System.loadLibrary(" in it.readText() },
            )
        }
    }

    /**
     * Only the Compose template needs the network, and it says which artifacts.
     *
     * Creating a project is something a phone does offline more often than a
     * laptop does, so a template that silently required a Maven resolve would
     * turn "New project" into an operation that fails on a train. There is one
     * that does, it is labelled in the picker, and this is what keeps the count
     * at one on purpose rather than by accident.
     */
    @Test
    fun `dependencies are declared only where they are the point`() {
        val withDependencies = ProjectTemplate.ALL.filter { it.dependencies.isNotEmpty() }

        assertEquals(
            "a template gained dependencies without the picker learning to warn about it",
            listOf(ComposeApp),
            withDependencies,
        )
        // `group:artifact:version`, which is what `Coordinate.parse` in
        // :engine:deps accepts. A two-part coordinate resolves to nothing and
        // reports an empty graph, not a parse error.
        ComposeApp.dependencies.forEach { coordinate ->
            assertEquals(
                "$coordinate is not group:artifact:version",
                3,
                coordinate.split(':').size,
            )
        }
    }

    /** Every language the picker can reach has at least one template. */
    @Test
    fun `every offered language has a template and a default`() {
        SourceLanguage.entries.forEach { language ->
            val forLanguage = ProjectTemplate.forLanguage(language)
            assertTrue("no template writes $language", forLanguage.isNotEmpty())
            assertEquals(
                "the default for $language is not one of its own templates",
                language,
                ProjectTemplate.defaultFor(language).language,
            )
        }
    }

    @Test
    fun `a template can be found by the id it records`() {
        ProjectTemplate.ALL.forEach { template ->
            assertEquals(template, ProjectTemplate.byId(template.id))
        }
        assertEquals(null, ProjectTemplate.byId("no-such-template"))
        assertEquals(null, ProjectTemplate.byId(null))
    }

    private companion object {
        /** Languages that run rather than build; they have no APK to produce. */
        val RUN_ONLY = setOf(
            SourceLanguage.JAVASCRIPT,
            SourceLanguage.CSHARP,
            SourceLanguage.PYTHON,
        )
    }
}
