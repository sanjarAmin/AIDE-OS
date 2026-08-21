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
        val layout = ProjectLayout.of(project)
        val packageDir = File(layout.javaDir, project.applicationId.replace('.', '/'))
        packageDir.mkdirs()
        layout.resourceDir.resolve("values").mkdirs()

        layout.manifestFile.writeText(manifest(project.applicationId))
        File(layout.resourceDir, "values/strings.xml").writeText(strings(project.name))

        when (project.language) {
            SourceLanguage.KOTLIN -> File(packageDir, "MainActivity.kt")
                .writeText(kotlinActivity(project.applicationId))
            else -> File(packageDir, "MainActivity.java")
                .writeText(javaActivity(project.applicationId))
        }
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
