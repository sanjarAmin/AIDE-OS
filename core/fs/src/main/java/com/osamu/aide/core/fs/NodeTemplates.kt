package com.osamu.aide.core.fs

import java.io.File

/**
 * The `package.json` every Node template needs.
 *
 * `main` is named explicitly rather than left to npm's default, because that
 * field is what a runner should consult -- guessing `index.js` works until
 * someone renames it, and then the failure is "cannot find module" naming a
 * file the user never wrote. `ProjectLayout.nodeEntryPoint` reads it.
 *
 * `private: true` so a stray `npm publish` cannot upload somebody's phone
 * project.
 */
private fun writePackageJson(
    project: Project,
    entryPoint: String,
    dependencies: Map<String, String> = emptyMap(),
) {
    val block = if (dependencies.isEmpty()) {
        ""
    } else {
        dependencies.entries.joinToString(
            separator = ",\n",
            prefix = ",\n  \"dependencies\": {\n",
            postfix = "\n  }",
        ) { (name, version) -> "    \"$name\": \"$version\"" }
    }

    File(project.rootDir, "package.json").writeText(
        """
        |{
        |  "name": "${project.name.lowercase().replace(Regex("[^a-z0-9-]"), "-")}",
        |  "version": "1.0.0",
        |  "private": true,
        |  "main": "$entryPoint",
        |  "scripts": {
        |    "start": "node $entryPoint"
        |  }$block
        |}
        """.trimMargin() + "\n",
    )
}

/**
 * One file, run it, read the output.
 *
 * The smallest runnable thing, and the default a JavaScript project gets when a
 * caller names a language and not a template -- an imported project, or a
 * `ProjectTemplate.write` from before the catalog existed. It is the Node
 * counterpart of [BasicJavaApp]: when one of the others misbehaves, this is the
 * control that says whether the runtime or the program is at fault.
 *
 * It prints `process.platform` and `process.arch` rather than "hello", because
 * those are the two facts a user is actually unsure of on a phone -- and
 * `android` / `arm64` in the run panel is the proof that this is Termux's build
 * of Node and not something else. `tools/node/FINDINGS.md`.
 */
object NodeScript : ProjectTemplate(
    id = "js-script",
    displayName = "Script",
    objective = "Run one file and read its output -- the smallest thing that runs.",
    language = SourceLanguage.JAVASCRIPT,
) {
    override fun write(project: Project) {
        project.rootDir.mkdirs()
        writePackageJson(project, entryPoint = "index.js")
        File(project.rootDir, "index.js").writeText(
            """
            console.log('Hello from ' + process.platform + ' on ' + process.arch);
            """.trimIndent() + "\n",
        )
        writeRunIgnores(project, extra = listOf("node_modules/"))
    }
}

/**
 * A program that keeps running, which is the thing a phone is unexpectedly
 * good at.
 *
 * The objective is a server the user can actually reach: it binds `127.0.0.1`
 * and prints the URL, so the next step is opening the device's own browser.
 * **Loopback rather than `0.0.0.0`** -- binding every interface puts a server
 * on whatever Wi-Fi the phone is joined to, which is a decision to take
 * deliberately and not one a template should take for someone.
 *
 * `node:http` and no dependencies, so it runs the moment it is created. It is
 * also the template that makes the ■ button meaningful: this is the first
 * project that does not exit on its own, and `RunSystem` kills the process when
 * the collection is cancelled precisely so the port is free next time.
 */
object NodeHttpServer : ProjectTemplate(
    id = "js-server",
    displayName = "HTTP server",
    objective = "Serve a page from the phone and open it in the browser.",
    language = SourceLanguage.JAVASCRIPT,
) {
    override fun write(project: Project) {
        project.rootDir.mkdirs()
        writePackageJson(project, entryPoint = "server.js")

        File(project.rootDir, "server.js").writeText(
            """
            const http = require('node:http');

            const HOST = '127.0.0.1';
            const PORT = 8080;

            let requests = 0;

            const server = http.createServer((request, response) => {
              requests += 1;
              response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
              response.end(
                '<h1>Served from Android</h1>' +
                '<p>' + request.method + ' ' + request.url + '</p>' +
                '<p>Request number ' + requests + '</p>'
              );
            });

            server.listen(PORT, HOST, () => {
              console.log('Listening on http://' + HOST + ':' + PORT);
              console.log('Open that in the browser. Stop the run to free the port.');
            });

            // Without this the port stays taken by a process nobody can see.
            // Stopping a run destroys the process, so this is the tidy path
            // rather than the only one -- but a server that ignores SIGTERM is
            // a server that has to be killed, and that is worth not teaching.
            process.on('SIGTERM', () => server.close(() => process.exit(0)));
            """.trimIndent() + "\n",
        )
        writeRunIgnores(project, extra = listOf("node_modules/"))
    }
}

/**
 * A script that takes arguments and touches the filesystem.
 *
 * The objective is the opposite end of Node from [NodeHttpServer]: something
 * that starts, does one job and exits with a code that means something. It is
 * the shape most on-device scripting actually takes -- rename these files,
 * count these lines -- and the one that makes the terminal and the editor
 * useful together.
 *
 * It exits non-zero on bad input on purpose. The run panel prints "Exited with
 * code 2", and a template that always exited zero would never show that the
 * code is reported at all.
 */
object NodeCommandLineTool : ProjectTemplate(
    id = "js-cli",
    displayName = "Command-line tool",
    objective = "Read arguments, work on files, and exit with a meaningful code.",
    language = SourceLanguage.JAVASCRIPT,
) {
    override fun write(project: Project) {
        project.rootDir.mkdirs()
        writePackageJson(project, entryPoint = "cli.js")

        File(project.rootDir, "cli.js").writeText(
            """
            const fs = require('node:fs');
            const path = require('node:path');

            // slice(2) because argv[0] is the runtime and argv[1] is this
            // script. Under AIDE-OS argv[0] is the *linker*, not node -- the
            // runtime is started through /system/bin/linker64 -- which is one
            // more reason never to read it. tools/node/FINDINGS.md
            const args = process.argv.slice(2);

            if (args.length === 0) {
              // No arguments is the default run, so it has to do something
              // worth watching rather than only complaining.
              const self = path.basename(__filename);
              console.log('No files given, so counting this script.');
              console.log('usage: cli.js <file>...   -- lines, words and bytes');
              args.push(self);
            }

            let failed = false;

            for (const name of args) {
              let text;
              try {
                text = fs.readFileSync(name, 'utf8');
              } catch (error) {
                // The message, not the stack: the user gave a bad path, which
                // is not a defect in this program.
                console.error(name + ': ' + error.message);
                failed = true;
                continue;
              }

              const lines = text.length === 0 ? 0 : text.split('\n').length;
              const words = text.split(/\s+/).filter(Boolean).length;
              console.log(
                String(lines).padStart(6) +
                String(words).padStart(8) +
                String(Buffer.byteLength(text)).padStart(9) +
                '  ' + name
              );
            }

            process.exit(failed ? 1 : 0);
            """.trimIndent() + "\n",
        )
        writeRunIgnores(project, extra = listOf("node_modules/"))
    }
}

/**
 * The only template that asks for something to be installed.
 *
 * The objective is the workflow, not the package: `npm install` reaches the
 * network, writes hundreds of megabytes into the project and is a separate
 * action from ▶ for exactly that reason -- see
 * `WorkspaceViewModel.installDependencies`. A user who has never seen that
 * button does not know the ▶ they pressed was never going to work.
 *
 * So the program **does not crash without it**. `require` is wrapped, and the
 * failure path prints the sentence that tells the user what to do. A template
 * that threw `Cannot find module 'picocolors'` would be teaching the user to
 * distrust a project they just created.
 *
 * `picocolors` is the dependency because it is two kilobytes with no
 * dependencies of its own, so the install finishes on a phone's connection and
 * the `node_modules` it produces is small enough to look at.
 */
object NodeDependencyDemo : ProjectTemplate(
    id = "js-npm",
    displayName = "npm dependency",
    objective = "Install a package from npm on the device and use it.",
    language = SourceLanguage.JAVASCRIPT,
) {
    override fun write(project: Project) {
        project.rootDir.mkdirs()
        writePackageJson(
            project,
            entryPoint = "index.js",
            dependencies = mapOf("picocolors" to "^1.1.1"),
        )

        File(project.rootDir, "index.js").writeText(
            """
            // Wrapped, because this project is created with the dependency
            // declared and not installed. An uncaught `Cannot find module` here
            // would look like a broken template rather than a missing step.
            let colors;
            try {
              colors = require('picocolors');
            } catch (error) {
              console.error('picocolors is not installed yet.');
              console.error('Use "Install dependencies" in the workspace menu, then run again.');
              process.exit(1);
            }

            console.log(colors.bold(colors.green('Dependencies are installed.')));
            console.log('node ' + process.versions.node + ' on ' + process.platform + '/' + process.arch);
            console.log(colors.dim('node_modules is in .gitignore, and in the file tree it is hidden.'));
            """.trimIndent() + "\n",
        )
        writeRunIgnores(project, extra = listOf("node_modules/"))
    }
}
