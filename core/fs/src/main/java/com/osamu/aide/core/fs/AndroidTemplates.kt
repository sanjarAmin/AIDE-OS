package com.osamu.aide.core.fs

import com.osamu.aide.core.fs.AndroidScaffold.ActivityEntry
import java.io.File

/**
 * The smallest app that proves the whole pipeline ran.
 *
 * Framework classes only -- no AndroidX -- so creating and building it needs no
 * dependency resolution and works offline. That is the entire point of having
 * it beside [ComposeApp]: when a Compose build fails, this is the control that
 * says whether the engine or the dependency graph is at fault.
 */
object BasicJavaApp : ProjectTemplate(
    id = "java-basic",
    displayName = "Basic app",
    objective = "Start from the smallest thing that builds: one screen, no dependencies.",
    language = SourceLanguage.JAVA,
) {
    override fun write(project: Project) {
        val packageDir = AndroidScaffold.scaffold(
            project = project,
            activities = listOf(ActivityEntry("MainActivity", launcher = true)),
            strings = mapOf("greeting" to "Hello from AIDE-OS"),
        )
        File(packageDir, "MainActivity.java").writeText(
            """
            package ${project.applicationId};

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
            """.trimIndent() + "\n",
        )
    }
}

/** [BasicJavaApp], on the Kotlin toolchain. */
object BasicKotlinApp : ProjectTemplate(
    id = "kotlin-basic",
    displayName = "Basic app",
    objective = "Start from the smallest thing that builds: one screen, no dependencies.",
    language = SourceLanguage.KOTLIN,
) {
    override fun write(project: Project) {
        val packageDir = AndroidScaffold.scaffold(
            project = project,
            activities = listOf(ActivityEntry("MainActivity", launcher = true)),
            strings = mapOf("greeting" to "Hello from AIDE-OS"),
        )
        File(packageDir, "MainActivity.kt").writeText(
            """
            package ${project.applicationId}

            import android.app.Activity
            import android.os.Bundle
            import android.widget.TextView

            class MainActivity : Activity() {

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(TextView(this).apply { setText(R.string.greeting) })
                }
            }
            """.trimIndent() + "\n",
        )
    }
}

/**
 * Two activities, and something travelling between them.
 *
 * The objective is the part of an Android app that has no equivalent in a
 * console program and that the one-screen templates cannot show: a second
 * component declared in the manifest, started by an `Intent`, reading an extra
 * the caller put there. It is also the first template whose manifest has more
 * than one `<activity>`, which is worth exercising -- a second entry with
 * `android:exported="true"` and no intent filter is a lint error in a real
 * project and an installed app with two launcher icons if it has one.
 *
 * Framework `ListView` rather than `RecyclerView`: that would be AndroidX, and
 * a template whose objective is navigation should not also be a download.
 */
object TwoScreenJavaApp : ProjectTemplate(
    id = "java-two-screens",
    displayName = "Two screens",
    objective = "Move between activities and pass data in an Intent.",
    language = SourceLanguage.JAVA,
) {
    override fun write(project: Project) {
        val packageDir = AndroidScaffold.scaffold(
            project = project,
            activities = listOf(
                ActivityEntry("MainActivity", launcher = true),
                ActivityEntry("DetailActivity"),
            ),
            strings = mapOf(
                "pick_one" to "Pick one",
                "nothing_selected" to "Nothing was selected",
            ),
        )
        val id = project.applicationId

        File(packageDir, "MainActivity.java").writeText(
            """
            package $id;

            import android.app.ListActivity;
            import android.content.Intent;
            import android.os.Bundle;
            import android.view.View;
            import android.widget.ArrayAdapter;
            import android.widget.ListView;

            public class MainActivity extends ListActivity {

                /** The key the two screens agree on. Spelled once, on purpose. */
                public static final String EXTRA_ITEM = "$id.ITEM";

                private static final String[] ITEMS = {
                    "Kotlin", "Java", "C", "C++", "JavaScript", "C#"
                };

                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setTitle(R.string.pick_one);
                    setListAdapter(new ArrayAdapter<>(
                        this, android.R.layout.simple_list_item_1, ITEMS));
                }

                @Override
                protected void onListItemClick(ListView list, View view, int position, long id) {
                    Intent intent = new Intent(this, DetailActivity.class);
                    intent.putExtra(EXTRA_ITEM, ITEMS[position]);
                    startActivity(intent);
                }
            }
            """.trimIndent() + "\n",
        )

        File(packageDir, "DetailActivity.java").writeText(
            """
            package $id;

            import android.app.Activity;
            import android.os.Bundle;
            import android.view.Gravity;
            import android.widget.TextView;

            public class DetailActivity extends Activity {

                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);

                    // getStringExtra returns null when the activity is started
                    // any other way -- from the launcher, or by the system
                    // restoring a task -- and a screen that crashes then would
                    // crash in exactly the case nobody tests.
                    String item = getIntent().getStringExtra(MainActivity.EXTRA_ITEM);

                    TextView text = new TextView(this);
                    text.setGravity(Gravity.CENTER);
                    text.setTextSize(28f);
                    if (item == null) {
                        text.setText(R.string.nothing_selected);
                    } else {
                        text.setText(item);
                    }
                    setContentView(text);
                }
            }
            """.trimIndent() + "\n",
        )
    }
}

/**
 * A declarative UI, and the only template that needs the network.
 *
 * **The version pair is a requirement, not tidiness.** Resolution here is
 * Maven's, which has no equivalent of the Gradle platform constraints the
 * AndroidX BOM applies, so a graph mixing eras keeps whatever each POM happened
 * to name and lands two copies of a class that moved between modules.
 * `engine/deps/FINDINGS.md` §1. These two are the pair `ComposeRunTest` builds,
 * installs and reads off the screen, which is the only reason to trust them.
 *
 * **Deliberately not material3.** A theme's worth of extra resources makes an
 * aapt2 failure and a Compose failure hard to tell apart, and it is tens of
 * megabytes more to resolve on a phone. `foundation` brings `BasicText`, and
 * `activity-compose` brings `ComponentActivity` and `setContent`, which is
 * everything the objective needs.
 *
 * The Compose compiler plugin is not configured here and must not be: the
 * Kotlin stage turns it on when it sees `androidx/compose/runtime/Composer` on
 * the classpath, so declaring the dependency *is* enabling the plugin.
 * `KotlinCompiler.usesCompose`.
 */
object ComposeApp : ProjectTemplate(
    id = "kotlin-compose",
    displayName = "Compose UI",
    objective = "Build a screen declaratively, with state that survives recomposition.",
    language = SourceLanguage.KOTLIN,
    dependencies = listOf(
        "androidx.activity:activity-compose:1.13.0",
        "androidx.compose.foundation:foundation-android:1.12.0",
    ),
) {
    override fun write(project: Project) {
        val packageDir = AndroidScaffold.scaffold(
            project = project,
            activities = listOf(ActivityEntry("MainActivity", launcher = true)),
            strings = mapOf("greeting" to "Hello from Compose"),
        )
        File(packageDir, "MainActivity.kt").writeText(
            """
            package ${project.applicationId}

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.clickable
            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.foundation.layout.padding
            import androidx.compose.foundation.text.BasicText
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.mutableIntStateOf
            import androidx.compose.runtime.remember
            import androidx.compose.runtime.setValue
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.res.stringResource
            import androidx.compose.ui.unit.dp

            class MainActivity : ComponentActivity() {

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent { Counter() }
                }
            }

            @Composable
            private fun Counter() {
                // `remember` is what makes this state and not a local: without
                // it the value is reset by every recomposition, which is the
                // first thing everyone gets wrong and the reason this template
                // counts rather than just drawing text.
                var taps by remember { mutableIntStateOf(0) }

                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // From resources, not a literal. Compose does not change
                    // what a string resource is -- it is still aapt2 linking it
                    // and R.java naming it, and this line is what proves both
                    // ran. stringResource reads it through the composition's
                    // own Context, so it follows a configuration change.
                    BasicText(text = stringResource(R.string.greeting))

                    BasicText(text = "Tapped ${'$'}taps times", modifier = Modifier.padding(top = 8.dp))
                    BasicText(
                        text = "Tap here",
                        modifier = Modifier.padding(top = 16.dp).clickable { taps++ },
                    )
                }
            }
            """.trimIndent() + "\n",
        )
    }
}

/**
 * Java calling C++, and the shared library that has to travel with it.
 *
 * The objective is the native build path end to end: clang compiles each source
 * on its own, we plan and run the link ourselves, and the result is packaged
 * into the APK where `System.loadLibrary` can find it.
 * `tools/clang/FINDINGS.md`.
 *
 * **No `__android_log_print`, and no other NDK library.** The link stage runs
 * `clang.link` with no extra `-l` arguments, so a template that logged would
 * fail at the link with an undefined symbol -- a good lesson and a terrible
 * first experience. Everything the native side has to say, it returns.
 *
 * The C++ side uses `std::string`, which is what separates this from
 * [CNativeLibraryApp]: the driver links `libc++_shared.so` and the build
 * packages it beside the project's own library, so the APK carries two.
 */
object CppNativeLibraryApp : ProjectTemplate(
    id = "cpp-native",
    displayName = "C++ native library",
    objective = "Call C++ from Java over JNI, and ship the library in the APK.",
    language = SourceLanguage.CPP,
) {
    override fun write(project: Project) {
        val packageDir = AndroidScaffold.scaffold(
            project = project,
            activities = listOf(ActivityEntry("MainActivity", launcher = true)),
            strings = mapOf("native_title" to "Native library"),
        )
        val id = project.applicationId

        File(packageDir, "MainActivity.java").writeText(
            """
            package $id;

            import android.app.Activity;
            import android.os.Bundle;
            import android.view.Gravity;
            import android.widget.TextView;

            public class MainActivity extends Activity {

                static {
                    // The name without `lib` and without `.so`: the loader adds
                    // both. It has to match the library the build produces,
                    // which is named after the project.
                    System.loadLibrary("native");
                }

                /** Implemented in src/main/cpp/native.cpp. */
                private native String describe(int value);

                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    // From resources, not a literal: it is what proves aapt2
                    // linked and R.java reached the compiler. The text below it
                    // comes from the native library instead.
                    setTitle(R.string.native_title);

                    TextView text = new TextView(this);
                    text.setGravity(Gravity.CENTER);
                    text.setTextSize(20f);
                    text.setText(describe(7));
                    setContentView(text);
                }
            }
            """.trimIndent() + "\n",
        )

        val nativeDir = ProjectLayout.of(project).nativeDir
        nativeDir.mkdirs()
        File(nativeDir, "native.cpp").writeText(
            """
            #include <jni.h>
            #include <string>

            // The name is the contract: Java_<package>_<class>_<method>, with
            // every `.` in the package replaced by `_`. Rename the class or
            // move the package and this stops being found -- the failure is an
            // UnsatisfiedLinkError at the call, not at load, which is why it
            // looks like the library is missing when it is not.
            extern "C" JNIEXPORT jstring JNICALL
            Java_${id.replace('.', '_')}_MainActivity_describe(
                    JNIEnv *env, jobject /* this */, jint value) {

                // std::string is why this project links libc++_shared.so, and
                // why the APK carries two native libraries instead of one.
                std::string message = "C++ squared " + std::to_string(value) +
                        " to " + std::to_string(value * value);

                return env->NewStringUTF(message.c_str());
            }
            """.trimIndent() + "\n",
        )
    }
}

/**
 * The same round trip in C, and the difference is what it is for.
 *
 * A pure C library links against nothing but libc, so **the APK carries one
 * native library instead of two** -- `NativeCompileStage.NativeOutput.runtime`
 * is empty where the C++ template's holds `libc++_shared.so`. On a project that
 * ships four ABIs that is megabytes, and it is the reason to write a JNI shim
 * in C even when the work behind it is C++.
 *
 * It also has to do its own string handling, because there is no `std::string`
 * to do it: `snprintf` into a fixed buffer is the idiom, and the buffer being
 * big enough is the caller's problem in a way it never is in the C++ version.
 */
object CNativeLibraryApp : ProjectTemplate(
    id = "c-native",
    displayName = "C native library",
    objective = "Use the C ABI over JNI -- no C++ runtime, so one .so in the APK.",
    language = SourceLanguage.C,
) {
    override fun write(project: Project) {
        val packageDir = AndroidScaffold.scaffold(
            project = project,
            activities = listOf(ActivityEntry("MainActivity", launcher = true)),
            strings = mapOf("native_title" to "Native library"),
        )
        val id = project.applicationId

        File(packageDir, "MainActivity.java").writeText(
            """
            package $id;

            import android.app.Activity;
            import android.os.Bundle;
            import android.view.Gravity;
            import android.widget.TextView;

            public class MainActivity extends Activity {

                static {
                    System.loadLibrary("native");
                }

                /** Implemented in src/main/cpp/native.c. */
                private native String describe(int value);

                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    // From resources, not a literal: it is what proves aapt2
                    // linked and R.java reached the compiler. The text below it
                    // comes from the native library instead.
                    setTitle(R.string.native_title);

                    TextView text = new TextView(this);
                    text.setGravity(Gravity.CENTER);
                    text.setTextSize(20f);
                    text.setText(describe(7));
                    setContentView(text);
                }
            }
            """.trimIndent() + "\n",
        )

        // `src/main/cpp` even though this is C: that is where the NDK's own
        // Gradle plugin puts both, so an imported project needs nothing moved.
        // ProjectLayout.nativeSources() matches on extension, not directory.
        val nativeDir = ProjectLayout.of(project).nativeDir
        nativeDir.mkdirs()
        File(nativeDir, "native.c").writeText(
            """
            #include <jni.h>
            #include <stdio.h>

            JNIEXPORT jstring JNICALL
            Java_${id.replace('.', '_')}_MainActivity_describe(
                    JNIEnv *env, jobject thiz, jint value) {

                // Fixed buffer and snprintf, because C has no growable string.
                // snprintf always terminates and never writes past the size,
                // which sprintf does not -- the difference is the whole reason
                // to prefer it.
                char message[64];
                snprintf(message, sizeof(message), "C squared %d to %d", value, value * value);

                // (*env)-> and not env->: the C JNI interface is a pointer to a
                // table of pointers, where the C++ one wraps it in a struct
                // with methods. Every JNI call differs between the two.
                return (*env)->NewStringUTF(env, message);
            }
            """.trimIndent() + "\n",
        )
    }
}
