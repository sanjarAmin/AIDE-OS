#!/usr/bin/env python3
"""Generate a synthetic Android project to measure build scaling.

generate-project.py OUT --modules M --classes C [--single]

The projects behind tools/bench/FINDINGS.md.

Each class has a few methods, fields and calls into the previous class and into
the previous module, so compilation cannot treat files independently. With
--single everything lands in one module laid out like an AIDE-OS template
(src/main at the root), which is what the fast engine builds; otherwise the
root is a Gradle project with :app depending on :lib0..:libM-1.
"""
import argparse, os, textwrap

p = argparse.ArgumentParser()
p.add_argument("out")
p.add_argument("--modules", type=int, default=8)
p.add_argument("--classes", type=int, default=100)
p.add_argument("--single", action="store_true")
p.add_argument("--agp", default="9.3.2")
p.add_argument("--name", default="Large")
a = p.parse_args()


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(textwrap.dedent(text).lstrip())


def java_class(pkg, index, prev_module_pkg):
    prev = f"C{index - 1}" if index > 0 else None
    calls = f"total += new {prev}().compute(seed + 1);" if prev else "total += seed;"
    cross = (f"total += new {prev_module_pkg}.C0().compute(seed);" if prev_module_pkg and index == 0 else "")
    methods = "\n".join(
        f"""
    public int step{m}(int value) {{
        int local{m} = value * {m + 1} + counter;
        for (int i = 0; i < {m + 2}; i++) {{
            local{m} += labels.get(i % labels.size()).length();
        }}
        return local{m};
    }}"""
        for m in range(6)
    )
    return f"""package {pkg};

import java.util.ArrayList;
import java.util.List;

public class C{index} {{
    private int counter = {index};
    private final List<String> labels = new ArrayList<>();

    public C{index}() {{
        labels.add("alpha{index}");
        labels.add("beta{index}");
        labels.add("gamma{index}");
    }}

    public int compute(int seed) {{
        int total = 0;
        if (seed > {index} + 64) return seed;
        {calls}
        {cross}
        total += step0(seed) + step1(seed) + step2(seed);
        return total;
    }}
{methods}
}}
"""


manifest_app = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="@string/app_name">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""

strings = """<resources>
    <string name="app_name">{name}</string>
</resources>
"""

app_pkg = "com.example.large"
activity = f"""package {app_pkg};

public class MainActivity extends android.app.Activity {{
    @Override
    protected void onCreate(android.os.Bundle state) {{
        super.onCreate(state);
        android.widget.TextView view = new android.widget.TextView(this);
        view.setText("total " + new {{last}}.C0().compute(1));
        setContentView(view);
    }}
}}
"""

out = a.out
if a.single:
    # One module, template layout: every "module" is a package.
    for m in range(a.modules):
        pkg = f"{app_pkg}.m{m}"
        prev = f"{app_pkg}.m{m - 1}" if m > 0 else None
        for c in range(a.classes):
            write(f"{out}/src/main/java/{pkg.replace('.', '/')}/C{c}.java", java_class(pkg, c, prev))
    write(f"{out}/src/main/java/{app_pkg.replace('.', '/')}/MainActivity.java",
          activity.replace("{last}", f"{app_pkg}.m{a.modules - 1}"))
    # The fast engine reads the package from the manifest, as AIDE-OS's own
    # template writes it; Gradle projects say `namespace` instead.
    write(f"{out}/src/main/AndroidManifest.xml",
          manifest_app.replace('android">', f'android" package="{app_pkg}">', 1))
    write(f"{out}/src/main/res/values/strings.xml", strings.format(name=a.name))
else:
    includes = ", ".join([f'":lib{m}"' for m in range(a.modules)] + ['":app"'])
    write(f"{out}/settings.gradle.kts", f"""
        pluginManagement {{ repositories {{ google(); mavenCentral() }} }}
        dependencyResolutionManagement {{ repositories {{ google(); mavenCentral() }} }}
        rootProject.name = "{a.name}"
        include({includes})
    """)
    write(f"{out}/build.gradle.kts", f"""
        plugins {{
            id("com.android.application") version "{a.agp}" apply false
            id("com.android.library") version "{a.agp}" apply false
        }}
    """)
    for m in range(a.modules):
        pkg = f"{app_pkg}.m{m}"
        prev = f"{app_pkg}.m{m - 1}" if m > 0 else None
        dep = f'dependencies {{ implementation(project(":lib{m - 1}")) }}' if m > 0 else ""
        write(f"{out}/lib{m}/build.gradle.kts", f"""
            plugins {{ id("com.android.library") }}
            android {{
                namespace = "{pkg}"
                compileSdk {{ version = release(36) }}
                defaultConfig {{ minSdk = 26 }}
            }}
            {dep}
        """)
        for c in range(a.classes):
            write(f"{out}/lib{m}/src/main/java/{pkg.replace('.', '/')}/C{c}.java", java_class(pkg, c, prev))
    write(f"{out}/app/build.gradle.kts", f"""
        plugins {{ id("com.android.application") }}
        android {{
            namespace = "{app_pkg}"
            compileSdk {{ version = release(36) }}
            defaultConfig {{ applicationId = "{app_pkg}"; minSdk = 26; versionCode = 1 }}
        }}
        dependencies {{ implementation(project(":lib{a.modules - 1}")) }}
    """)
    write(f"{out}/app/src/main/java/{app_pkg.replace('.', '/')}/MainActivity.java",
          activity.replace("{last}", f"{app_pkg}.m{a.modules - 1}"))
    write(f"{out}/app/src/main/AndroidManifest.xml", manifest_app)
    write(f"{out}/app/src/main/res/values/strings.xml", strings.format(name=a.name))

lines = 0
for root, _, files in os.walk(out):
    for f in files:
        if f.endswith(".java"):
            lines += sum(1 for _ in open(os.path.join(root, f)))
print(f"{out}: {a.modules} modules x {a.classes} classes, {lines} lines of Java")
