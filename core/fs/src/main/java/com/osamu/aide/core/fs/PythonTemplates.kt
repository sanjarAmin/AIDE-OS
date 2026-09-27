package com.osamu.aide.core.fs

import java.io.File

/**
 * What a Python run leaves behind, on top of the shared set.
 *
 * `.aide-packages` is pip's `--target` directory and `__pycache__` is the
 * bytecode cache. The run sets `PYTHONDONTWRITEBYTECODE`, so the second should
 * never appear -- but a user who runs the interpreter from the terminal instead
 * is not covered by that, and an ignore rule costs nothing.
 */
private fun pythonIgnores(project: Project, extra: List<String> = emptyList()) =
    writeRunIgnores(
        project,
        extra = extra + listOf("${ProjectLayout.PYTHON_PACKAGES}/", "__pycache__/", "*.pyc"),
    )

/**
 * One file, run it, read the output.
 *
 * The smallest runnable thing, and the default a Python project gets. It is the
 * control the other Python templates are debugged against, exactly as
 * [BasicJavaApp] is for the Android ones and [NodeScript] is for Node.
 *
 * It prints `sys.version` and `platform.machine()` because those are the two
 * facts a user is actually unsure of on a phone -- and seeing `Android` and
 * `aarch64` in the run panel is the proof that this is a Bionic CPython started
 * through the linker and not something else. `tools/python/FINDINGS.md`.
 */
object PythonScript : ProjectTemplate(
    id = "py-script",
    displayName = "Script",
    objective = "Run one file and read its output -- the smallest thing that runs.",
    language = SourceLanguage.PYTHON,
) {
    override fun write(project: Project) {
        project.rootDir.mkdirs()
        File(project.rootDir, "main.py").writeText(
            """
            import platform
            import sys


            def main() -> None:
                print("Hello from", platform.system(), "on", platform.machine())
                print("Python", sys.version.split()[0])


            # The guard is not ceremony. Without it every statement above runs
            # again the moment another file imports this one, which is how a
            # script that worked becomes a module that prints twice.
            if __name__ == "__main__":
                main()
            """.trimIndent() + "\n",
        )
        pythonIgnores(project)
    }
}

/**
 * A script that takes arguments and touches the filesystem.
 *
 * The objective is the shape most on-device scripting actually takes: rename
 * these files, count these lines, pull this field out of that JSON. It is also
 * the template that makes the terminal and the editor useful together, and the
 * one that shows the exit code is reported -- a bad path exits non-zero and the
 * run panel says so.
 *
 * `argparse` rather than reading `sys.argv` by hand, because it is in the
 * standard library, it writes the usage message for free, and the alternative
 * is the single most reinvented wheel in Python.
 */
object PythonCommandLineTool : ProjectTemplate(
    id = "py-cli",
    displayName = "Command-line tool",
    objective = "Parse arguments with argparse, work on files, exit meaningfully.",
    language = SourceLanguage.PYTHON,
) {
    override fun write(project: Project) {
        project.rootDir.mkdirs()
        File(project.rootDir, "main.py").writeText(
            """
            import argparse
            import pathlib
            import sys


            def count(path: pathlib.Path) -> tuple[int, int, int]:
                text = path.read_text(encoding="utf-8", errors="replace")
                return (
                    len(text.splitlines()),
                    len(text.split()),
                    len(text.encode("utf-8")),
                )


            def main() -> int:
                parser = argparse.ArgumentParser(description="Count lines, words and bytes.")
                # nargs="*" and not "+": a run with no arguments is the default
                # one, and a template whose default run is a usage error teaches
                # the wrong thing about the button that started it.
                parser.add_argument("files", nargs="*", type=pathlib.Path)
                arguments = parser.parse_args()

                paths = arguments.files or [pathlib.Path(__file__)]
                if not arguments.files:
                    print("No files given, so counting this script.")

                failed = False
                for path in paths:
                    try:
                        lines, words, size = count(path)
                    except OSError as error:
                        # The message, not a traceback: the user gave a bad
                        # path, which is not a defect in this program.
                        print(f"{path}: {error.strerror}", file=sys.stderr)
                        failed = True
                        continue
                    print(f"{lines:6} {words:7} {size:8}  {path}")

                # The exit code is the program's only machine-readable output,
                # and the run panel prints it.
                return 1 if failed else 0


            if __name__ == "__main__":
                raise SystemExit(main())
            """.trimIndent() + "\n",
        )
        pythonIgnores(project)
    }
}

/**
 * The only Python template that asks for something to be installed.
 *
 * The objective is the workflow, not the package. `pip install` reaches the
 * network and is a separate action from ▶ for the same reason `npm install` is:
 * a user who has never seen that button does not know the ▶ they pressed was
 * never going to work.
 *
 * So the program **does not crash without it**. The import is guarded and the
 * failure path prints the sentence that says what to do. A template that threw
 * `ModuleNotFoundError` on its first run would teach the user to distrust a
 * project they just created.
 *
 * `requirements.txt` and not `pyproject.toml`: the first is what
 * `pip install -r` reads directly, and the second describes a *build* performed
 * by a backend this app does not run. Writing the file that matches what
 * actually happens is the same rule that keeps a `.csproj` out of
 * [MonoConsoleApp].
 *
 * `idna` is the dependency because it is pure Python with no dependencies of
 * its own -- **a wheel with a C extension cannot be installed here**, since
 * building one needs headers this archive drops and a linker clang cannot
 * spawn. `tools/python/FINDINGS.md` §5.
 */
object PythonDependencyDemo : ProjectTemplate(
    id = "py-pip",
    displayName = "pip dependency",
    objective = "Install a package from PyPI on the device and import it.",
    language = SourceLanguage.PYTHON,
) {
    override fun write(project: Project) {
        project.rootDir.mkdirs()

        File(project.rootDir, "requirements.txt").writeText(
            """
            # Pinned, not floating. A phone resolves this over a connection that
            # may be metered, and a project that installs a different version
            # each time is one whose failures cannot be reproduced.
            #
            # Pure Python on purpose: a wheel with a C extension cannot be built
            # on device. tools/python/FINDINGS.md
            idna==3.10
            """.trimIndent() + "\n",
        )

        File(project.rootDir, "main.py").writeText(
            """
            # Guarded, because this project is created with the dependency
            # declared and not installed. An uncaught ModuleNotFoundError here
            # would look like a broken template rather than a missing step.
            try:
                import idna
            except ModuleNotFoundError:
                raise SystemExit(
                    "idna is not installed yet.\n"
                    'Use "Install dependencies" in the workspace menu, then run again.'
                )

            import sys


            def main() -> None:
                print("Dependencies are installed.")
                # An internationalised domain name, which is the whole reason
                # this library exists: the bytes on the wire are ASCII and the
                # name the user typed is not.
                name = "bücher.example"
                encoded = idna.encode(name).decode("ascii")
                print(f"{name} -> {encoded}")
                print(f"and back -> {idna.decode(encoded)}")
                print("installed into", [p for p in sys.path if "aide-packages" in p])


            if __name__ == "__main__":
                main()
            """.trimIndent() + "\n",
        )
        pythonIgnores(project)
    }
}
