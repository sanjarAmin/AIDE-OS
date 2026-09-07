package com.osamu.aide.core.fs

import java.io.File

/**
 * Writes the starting contents of a new project.
 *
 * The generated app deliberately reads a string resource from Java. That single
 * line is what makes the template a real test of the build engine rather than a
 * placeholder: it only compiles if aapt2 linked the resources, generated R.java,
 * and the Java compiler was handed it. A template with no resource reference
 * would still build if half the pipeline were broken.
 *
 * It also uses only framework classes -- no AndroidX -- so that creating and
 * building a project needs no dependency resolution and works offline.
 */
object ProjectTemplate {

    fun write(project: Project) {
        // **A JavaScript project is not an Android app**, and writing one as if
        // it were is worse than refusing. It has no manifest, no resources and
        // no `applicationId` that means anything; it has an entry point and a
        // `package.json`. Handled before the Android layout is touched, because
        // every line below assumes an APK.
        //
        // It reached `else ->` until 2026-09-07 and got a Java Activity --
        // harmless only because the picker offers Java and Kotlin alone, which
        // is exactly the kind of trap that survives until someone adds a chip.
        if (project.language == SourceLanguage.JAVASCRIPT) {
            writeNodeProject(project)
            return
        }
        // The same reasoning, for the same reason: a C# console app is not an
        // Android app either.
        if (project.language == SourceLanguage.CSHARP) {
            writeCSharpProject(project)
            return
        }

        val layout = ProjectLayout.of(project)
        val packageDir = File(layout.javaDir, project.applicationId.replace('.', '/'))
        packageDir.mkdirs()
        layout.resourceDir.resolve("values").mkdirs()

        layout.manifestFile.writeText(manifest(project.applicationId))
        File(layout.resourceDir, "values/strings.xml").writeText(strings(project.name))

        when (project.language) {
            SourceLanguage.KOTLIN -> File(packageDir, "MainActivity.kt")
                .writeText(kotlinActivity(project.applicationId))
            // C and C++ land here on purpose: a JNI project is a Java app with
            // native sources beside it, so the Activity is exactly right.
            else -> File(packageDir, "MainActivity.java")
                .writeText(javaActivity(project.applicationId))
        }
    }

    /**
     * A Node project: an entry point, and the manifest npm actually reads.
     *
     * `package.json` names `index.js` as `main` rather than relying on the
     * default, because that field is what a runner should consult -- guessing
     * `index.js` works until someone renames it, and then the failure is
     * "cannot find module" naming a file the user never wrote.
     *
     * `private: true` so a stray `npm publish` cannot upload somebody's phone
     * project, and no dependencies so that creating one works offline, for the
     * same reason the Android template uses no AndroidX.
     */
    private fun writeNodeProject(project: Project) {
        project.rootDir.mkdirs()
        File(project.rootDir, "package.json").writeText(
            """
            {
              "name": "${project.name.lowercase().replace(Regex("[^a-z0-9-]"), "-")}",
              "version": "1.0.0",
              "private": true,
              "main": "index.js",
              "scripts": {
                "start": "node index.js"
              }
            }
            """.trimIndent() + "\n",
        )
        File(project.rootDir, "index.js").writeText(
            """
            console.log('Hello from ' + process.platform + ' on ' + process.arch);
            """.trimIndent() + "\n",
        )
        writeRunIgnores(project, extra = listOf("node_modules/"))
    }

    /**
     * A C# console app: one source file, and nothing else.
     *
     * **No `.csproj`.** The MSBuild project format is what `dotnet` reads, and
     * there is no `dotnet` here -- mono's `mcs` takes a list of source files
     * and an output path, which is what `MonoRunSystem` gives it. Writing a
     * project file this app cannot read would be writing a file that lies
     * about how the project is built. `tools/mono/FINDINGS.md`, spike R14.
     */
    private fun writeCSharpProject(project: Project) {
        project.rootDir.mkdirs()
        File(project.rootDir, "Program.cs").writeText(
            """
            using System;

            class Program
            {
                static void Main(string[] args)
                {
                    Console.WriteLine("Hello from " + Environment.OSVersion.Platform);
                }
            }
            """.trimIndent() + "\n",
        )
        writeRunIgnores(project, extra = emptyList())
    }

    /**
     * What a run leaves behind, and must not be committed.
     *
     * Shared by the two languages that run rather than build, because both put
     * their working directories inside the project -- see [ProjectLayout.nodeHome].
     * The file tree hides them; the Git panel would not.
     */
    private fun writeRunIgnores(project: Project, extra: List<String>) {
        File(project.rootDir, ".gitignore").writeText(
            (
                extra + listOf(
                    "${ProjectLayout.NODE_HOME}/",
                    "${ProjectLayout.NODE_CACHE}/",
                    "${ProjectLayout.BUILD}/",
                )
                ).joinToString(separator = "\n", postfix = "\n"),
        )
    }

    private fun manifest(applicationId: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="$applicationId"
            android:versionCode="1"
            android:versionName="1.0">

            <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />

            <application
                android:label="@string/app_name"
                android:theme="@android:style/Theme.Material.Light">
                <activity
                    android:name=".MainActivity"
                    android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent() + "\n"

    private fun strings(name: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="app_name">${name.xmlEscaped()}</string>
            <string name="greeting">Hello from AIDE-OS</string>
        </resources>
    """.trimIndent() + "\n"

    private fun javaActivity(applicationId: String): String = """
        package $applicationId;

        import android.app.Activity;
        import android.os.Bundle;
        import android.widget.TextView;

        public class MainActivity extends Activity {

            @Override
            protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                TextView text = new TextView(this);
                text.setText(R.string.greeting);
                setContentView(text);
            }
        }
    """.trimIndent() + "\n"

    private fun kotlinActivity(applicationId: String): String = """
        package $applicationId

        import android.app.Activity
        import android.os.Bundle
        import android.widget.TextView

        class MainActivity : Activity() {

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(TextView(this).apply { setText(R.string.greeting) })
            }
        }
    """.trimIndent() + "\n"

    private fun String.xmlEscaped(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
