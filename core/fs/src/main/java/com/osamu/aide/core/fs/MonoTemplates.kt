package com.osamu.aide.core.fs

import java.io.File

/**
 * A C# console app: source files, and nothing else.
 *
 * **No `.csproj`.** The MSBuild project format is what `dotnet` reads, and there
 * is no `dotnet` here -- mono's `mcs` takes a list of source files and an output
 * path, which is what `MonoRunSystem` gives it. Writing a project file this app
 * cannot read would be writing a file that lies about how the project is built.
 * `tools/mono/FINDINGS.md`, spike R14.
 *
 * The objective is the language itself, so it stays to one file and shows the
 * parts of C# that have no counterpart in the Java templates beside it: get-only
 * auto-properties, named tuples, `out var`, and interpolation with alignment
 * and format specifiers.
 *
 * **Every construct here was compiled by the mcs this app ships before it was
 * written down.** That is not caution for its own sake: `lib/mono/4.5/mcs.exe`
 * is *not* a full C# 7 compiler, let alone a modern one. Tuples and `out var`
 * are accepted; `when` clauses in a switch and local functions -- C# 7.0, the
 * same release -- are rejected with `CS1525`, and `record` and switch
 * expressions are far out of reach. The first draft of this template used a
 * `record` and a switch expression and did not compile.
 * `tools/mono/FINDINGS.md` §6, and `MonoTemplateBuildTest` is what keeps it
 * true.
 */
object MonoConsoleApp : ProjectTemplate(
    id = "cs-console",
    displayName = "Console app",
    objective = "Learn the C# language: classes, tuples, interpolation, out var.",
    language = SourceLanguage.CSHARP,
) {
    override fun write(project: Project) {
        project.rootDir.mkdirs()
        File(project.rootDir, "Program.cs").writeText(
            """
            using System;
            using System.Collections.Generic;

            // A plain class with get-only auto-properties, which is what this compiler
            // gives you instead of a `record`: mono's mcs is not a full C# 7 compiler and
            // knows nothing of C# 9. tools/mono/FINDINGS.md
            class Reading
            {
                public string Name { get; }
                public double Celsius { get; }

                public Reading(string name, double celsius)
                {
                    Name = name;
                    Celsius = celsius;
                }

                // An expression-bodied member, which mcs does support.
                public override string ToString() => ${'$'}"{Name} at {Celsius:F1} C";
            }

            class Program
            {
                static void Main(string[] args)
                {
                    Console.WriteLine(${'$'}"Running on {Environment.OSVersion.Platform}, CLR {Environment.Version}");
                    Console.WriteLine();

                    var readings = new List<Reading>
                    {
                        new Reading("Freezer", -18.0),
                        new Reading("Room", 21.5),
                        new Reading("Oven", 180.0),
                    };

                    foreach (var reading in readings)
                    {
                        // Interpolation with alignment and a format specifier: the part of
                        // the syntax worth learning, since `{value,-8}` and `{value:F1}`
                        // are what replace composite format strings.
                        Console.WriteLine(${'$'}"{reading.Name,-8} {reading.Celsius,7:F1} C  {Verdict(reading.Celsius)}");
                    }

                    Console.WriteLine();

                    // A tuple with named elements, returned from a method. mcs does support
                    // these even though it rejects other C# 7 syntax.
                    var range = Range(readings);
                    Console.WriteLine(${'$'}"coldest {range.Low:F1} C, hottest {range.High:F1} C");

                    // `out var`, which saves declaring the variable on its own line.
                    if (args.Length > 0 && double.TryParse(args[0], out var asked))
                    {
                        Console.WriteLine(${'$'}"you asked about {asked:F1} C, which is {Verdict(asked)}");
                    }
                }

                static string Verdict(double celsius)
                {
                    // A conditional chain rather than a switch expression: mcs has no
                    // `switch` expressions and no `when` clauses in a switch statement.
                    return celsius < 0 ? "frozen"
                        : celsius < 30 ? "comfortable"
                        : "hot";
                }

                static (double Low, double High) Range(List<Reading> readings)
                {
                    double low = double.MaxValue, high = double.MinValue;
                    foreach (var reading in readings)
                    {
                        if (reading.Celsius < low) low = reading.Celsius;
                        if (reading.Celsius > high) high = reading.Celsius;
                    }
                    return (low, high);
                }
            }
            """.trimIndent() + "\n",
        )
        writeRunIgnores(project)
    }
}

/**
 * The class library, which is most of what the 117 MB archive is.
 *
 * `Console.WriteLine` proves the runtime started and nothing else. This proves
 * the rest of the install arrived: LINQ, generic collections, `System.IO` and
 * culture-aware formatting all live in assemblies that are separate files in
 * `lib/mono`, and an archive trimmed too far fails here rather than in
 * [MonoConsoleApp]. `tools/mono/FINDINGS.md` §5 is the record of what was cut.
 *
 * It writes and reads a file under the project as well, because `System.IO` is
 * the part of the library most likely to be broken by the install: mono resolves
 * its own paths through a config file that hardcodes Termux's prefix, and
 * `MonoToolchain` rewrites `\$mono_libdir` in it for exactly that reason.
 */
object MonoCollectionsApp : ProjectTemplate(
    id = "cs-linq",
    displayName = "LINQ and files",
    objective = "Query collections with LINQ and read a file back through System.IO.",
    language = SourceLanguage.CSHARP,
) {
    override fun write(project: Project) {
        project.rootDir.mkdirs()
        File(project.rootDir, "Program.cs").writeText(
            """
            using System;
            using System.Collections.Generic;
            using System.IO;
            using System.Linq;

            class Program
            {
                static void Main(string[] args)
                {
                    var commits = new List<(string Author, int Lines)>
                    {
                        ("ada", 120), ("grace", 340), ("ada", 90),
                        ("alan", 15), ("grace", 210), ("alan", 60),
                    };

                    // LINQ is the objective: a query over objects that reads as
                    // one expression. `OrderByDescending` then `Select` is
                    // deferred until the foreach enumerates it -- nothing above
                    // this line has run yet.
                    var byAuthor = commits
                        .GroupBy(commit => commit.Author)
                        .Select(group => new
                        {
                            Author = group.Key,
                            Commits = group.Count(),
                            Lines = group.Sum(commit => commit.Lines),
                        })
                        .OrderByDescending(entry => entry.Lines);

                    Console.WriteLine("author    commits   lines");
                    foreach (var entry in byAuthor)
                    {
                        Console.WriteLine(${'$'}"{entry.Author,-8} {entry.Commits,8} {entry.Lines,7}");
                    }

                    // System.IO against the project's own directory. The working
                    // directory is the project root when the run starts, so a
                    // relative path lands where the file tree will show it.
                    var report = "report.txt";
                    File.WriteAllLines(report, byAuthor.Select(e => ${'$'}"{e.Author}={e.Lines}"));
                    Console.WriteLine();
                    Console.WriteLine(${'$'}"Wrote {report}, {new FileInfo(report).Length} bytes:");
                    Console.WriteLine(File.ReadAllText(report).TrimEnd());
                }
            }
            """.trimIndent() + "\n",
        )
        writeRunIgnores(project, extra = listOf("report.txt"))
    }
}
