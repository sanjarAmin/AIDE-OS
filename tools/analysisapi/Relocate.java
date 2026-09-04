import org.jetbrains.org.objectweb.asm.AnnotationVisitor;
import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassVisitor;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.commons.ClassRemapper;
import org.jetbrains.org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites the Analysis API's references onto the compiler's relocated namespace.
 *
 * `kotlin-compiler-embeddable` shades the IntelliJ platform, guava,
 * opentelemetry and picocontainer under `org/jetbrains/kotlin/`. The Analysis
 * API is built against the *unshaded* originals, so the two cannot share a
 * classpath as published: the API asks for `com/intellij/psi/PsiElement` and
 * what exists is `org/jetbrains/kotlin/com/intellij/psi/PsiElement`.
 *
 * `tools/analysisapi/FINDINGS.md` §3 establishes that every target is present,
 * so this is a rename rather than a port.
 *
 * **Why ASM rather than the byte patching next door.**
 * `build-kotlinc-dex.py` patches the constant pool in place and asserts that
 * every rewrite is the same length, because a class name in a constant pool is
 * length-prefixed UTF-8 — change the bytes without changing the count and the
 * pool is corrupt. These prefixes grow by 21 characters, so that technique
 * cannot be used at all here and a real reassembly is required.
 *
 * The ASM this needs is already inside `kotlin-compiler-embeddable`
 * (`org/jetbrains/org/objectweb/asm`, `ClassRemapper` included), so it is on
 * the classpath this runs with and adds no dependency to the project.
 *
 * Usage: java -cp <compiler-jar> Relocate.java <in.jar> <out.jar>
 */
public final class Relocate {

    /**
     * The prefixes the compiler shades, longest first.
     *
     * Order matters: a plain prefix match would rewrite an already-relocated
     * name a second time, producing `org/jetbrains/kotlin/org/jetbrains/kotlin/…`.
     * {@link #alreadyRelocated} is what actually prevents that; the ordering
     * keeps the intent readable.
     */
    private static final String[] SHADED = {
        "com/intellij/",
        "com/google/common/",
        "io/opentelemetry/",
        "org/picocontainer/",
        // **The one that was missed, and the way it presented is the lesson.**
        // `jdeps` reports these classes as unresolved exactly like a genuinely
        // absent library, so the first response was to add
        // kotlinx-collections-immutable as a dependency -- which made the error
        // go away and put a *second, unrelocated* copy on the classpath.
        //
        // Nothing failed for a long time after that. The compiler's own FIR
        // signatures take the shaded type, so the mismatch only surfaced deep
        // inside analysis as
        //   NoSuchMethodError: FirSupertypeResolverVisitor.<init>(…,
        //     kotlinx.collections.immutable.PersistentList, …)
        // against a compiler whose constructor is identical but for that one
        // parameter being org.jetbrains.kotlin.kotlinx.collections.immutable.
        //
        // A library that is shaded here and added there does not fail to link.
        // It fails to *match*, later, somewhere else.
        "kotlinx/collections/immutable/",
    };

    private static final String PREFIX = "org/jetbrains/kotlin/";

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: Relocate <in.jar> <out.jar>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);

        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                if (alreadyRelocated(internalName)) return internalName;
                for (String shaded : SHADED) {
                    if (internalName.startsWith(shaded)) return PREFIX + internalName;
                }
                return internalName;
            }

            /**
             * String constants that are really JVM signatures.
             *
             * Kotlin compiles a callable reference — `KaFirPackageSymbol::psi`
             * — into a `PropertyReference1Impl` built with the member's
             * signature *as a string*: `"getPsi()Lcom/intellij/psi/PsiElement;"`.
             * ASM cannot rewrite that, and is right not to: it has no way to
             * tell a signature from a message or a resource path.
             *
             * Left alone the class still links, because the bytecode around it
             * was relocated — and then reflection looks up a member by a
             * descriptor naming a class that is not there, and the reference
             * fails at the moment it is used rather than when it is created.
             *
             * The guard is deliberately narrow: the string must contain a
             * shaded prefix in *descriptor position* (`Lcom/intellij/…`) and
             * look like a signature (`(`, or ending in `;`). A plain sentence
             * mentioning the package is left alone.
             */
            @Override
            public Object mapValue(Object value) {
                if (value instanceof String text && looksLikeSignature(text)) {
                    return relocateInternal(text);
                }
                return super.mapValue(value);
            }
        };

        int rewritten = 0;
        int copied = 0;
        Files.createDirectories(out.toAbsolutePath().getParent());

        try (ZipFile zip = new ZipFile(in.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                byte[] bytes;
                try (InputStream is = zip.getInputStream(entry)) {
                    bytes = is.readAllBytes();
                }

                String name = entry.getName();
                if (name.endsWith(".class")) {
                    ClassReader reader = new ClassReader(bytes);
                    // COMPUTE_NOTHING: this only renames types, so no frame or
                    // stack size changes -- and recomputing frames would need
                    // the whole classpath resolvable, which at this point it is
                    // deliberately not.
                    ClassWriter writer = new ClassWriter(0);
                    reader.accept(new MetadataRemapper(new ClassRemapper(writer, remapper)), 0);
                    bytes = writer.toByteArray();
                    rewritten++;
                } else {
                    // META-INF/services files name implementation classes as
                    // text. They are how the Analysis API is discovered at all,
                    // so a rename that skipped them would produce a jar that
                    // links and registers nothing.
                    if (name.startsWith("META-INF/services/")) {
                        bytes = relocateText(bytes);
                    }
                    // The plugin descriptors under META-INF/analysis-api are
                    // the same hazard in XML: `serviceInterface` and
                    // `serviceImplementation` are class names, and a descriptor
                    // naming a class that is not there registers nothing and
                    // says nothing.
                    //
                    // Note what must *not* move: `defaultExtensionNs="com.intellij"`
                    // is an extension-point namespace, a string the platform
                    // matches on, not a type. Relocating it would unregister
                    // every extension in the file. The dotted rewrite is
                    // anchored on a trailing dot for exactly that reason --
                    // `com.intellij.` matches the class, `com.intellij"` does
                    // not match the namespace.
                    if (name.startsWith("META-INF/analysis-api/") && name.endsWith(".xml")) {
                        bytes = relocateDottedBytes(bytes);
                    }
                    copied++;
                }

                // The service *file names* are class names too.
                if (name.startsWith("META-INF/services/")) {
                    name = "META-INF/services/" + relocateDotted(
                        name.substring("META-INF/services/".length()));
                }

                ZipEntry copy = new ZipEntry(name);
                // A fixed timestamp so the output is byte-identical between
                // runs, which is what lets it be checksummed and pinned.
                copy.setTime(0L);
                zos.putNextEntry(copy);
                zos.write(bytes);
                zos.closeEntry();
            }
        }
        System.out.printf("%s -> %s (%d classes rewritten, %d resources)%n",
            in.getFileName(), out.getFileName(), rewritten, copied);
    }

    /**
     * Rewrites the class names inside Kotlin's `@Metadata`, which ASM does not.
     *
     * **This is the half that fails silently.** `ClassRemapper` fixes the
     * constant pool, so the relocated jars link and run correctly on any JVM —
     * `@Metadata` means nothing to the JVM. It means everything to the *Kotlin
     * compiler*, which reads it to reconstruct a library's declarations. Left
     * alone, `analyze(element)` resolves its parameter to
     * `com.intellij.psi.PsiElement`, a class that is not on the classpath under
     * that name, and the error names a type nobody wrote.
     *
     * Only `d2` is rewritten, and that is not an approximation: `d1` is
     * protobuf whose class references are *indices into `d2`*, so `d2` is the
     * string table and correcting it corrects everything that points at it.
     */
    private static final class MetadataRemapper extends ClassVisitor {

        MetadataRemapper(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        /**
         * The SMAP too, which ASM also leaves alone.
         *
         * `SourceDebugExtension` carries Kotlin's inline-function line mapping
         * and names the source classes as text. Nothing links against it and
         * `d8` may well drop it, so this is cosmetic — but a stale name here
         * would misattribute an inlined frame in a stack trace to a class that
         * does not exist, and chasing that once would cost more than the three
         * lines it takes to keep it honest.
         */
        @Override
        public void visitSource(String source, String debug) {
            super.visitSource(source, debug == null ? null : relocateInternal(debug));
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            AnnotationVisitor next = super.visitAnnotation(descriptor, visible);
            if (next == null || !CARRIES_CLASS_NAMES.contains(descriptor)) return next;
            return new StringRelocatingAnnotationVisitor(next);
        }
    }

    /**
     * Annotations whose *string* values name classes.
     *
     * Two, and both were found the hard way.
     *
     * `kotlin/Metadata` is how the Kotlin compiler reads a library's
     * declarations; its `d2` is the string table that `d1`'s protobuf indexes
     * into. Leave it and the jars run correctly while Kotlin code compiled
     * against them resolves parameters to classes that are not there.
     *
     * `kotlin/jvm/internal/SourceDebugExtension` duplicates, as an annotation,
     * the SMAP already written as a class attribute. Fixing only the attribute
     * — which is what {@link MetadataRemapper#visitSource} does — leaves this
     * copy behind, and the residue looks exactly like the fix not working.
     */
    private static final java.util.Set<String> CARRIES_CLASS_NAMES = java.util.Set.of(
        "Lkotlin/Metadata;",
        "Lkotlin/jvm/internal/SourceDebugExtension;");

    /** Relocates every string in an annotation, including inside arrays. */
    private static final class StringRelocatingAnnotationVisitor extends AnnotationVisitor {

        StringRelocatingAnnotationVisitor(AnnotationVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof String) value = relocateInternal((String) value);
            super.visit(name, value);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            AnnotationVisitor array = super.visitArray(name);
            return array == null ? null : new StringRelocatingAnnotationVisitor(array);
        }
    }

    /**
     * Relocates every shaded prefix occurring anywhere in [text].
     *
     * A `d2` entry is not always a bare class name — it is as often a
     * descriptor like `Lcom/intellij/psi/PsiElement;` or a member signature —
     * so this substitutes wherever the prefix appears rather than only at the
     * start.
     */
    private static String relocateInternal(String text) {
        String result = text;
        for (String shaded : SHADED) {
            int from = 0;
            StringBuilder out = null;
            while (true) {
                int at = result.indexOf(shaded, from);
                if (at < 0) break;
                // Skip an occurrence that is already the tail of a relocated
                // name, or this would produce org/jetbrains/kotlin/org/...
                boolean relocated = at >= PREFIX.length()
                    && result.startsWith(PREFIX, at - PREFIX.length());
                if (relocated) {
                    from = at + shaded.length();
                    continue;
                }
                if (out == null) out = new StringBuilder(result.length() + 32);
                out.setLength(0);
                out.append(result, 0, at).append(PREFIX).append(result.substring(at));
                result = out.toString();
                from = at + PREFIX.length() + shaded.length();
            }
        }
        return result;
    }

    private static boolean alreadyRelocated(String internalName) {
        return internalName.startsWith(PREFIX);
    }

    /**
     * Whether a string constant is a JVM signature naming a shaded class.
     *
     * Both halves matter. Without the descriptor test — `L` immediately before
     * the package — any prose mentioning the package would be rewritten. Without
     * the shape test, so would a bare class name that happened to appear in a
     * message. Together they admit `getPsi()Lcom/intellij/psi/PsiElement;` and
     * turn away "see com/intellij/psi for details".
     */
    private static boolean looksLikeSignature(String text) {
        boolean namesShadedType = false;
        for (String shaded : SHADED) {
            if (text.contains("L" + shaded)) { namesShadedType = true; break; }
        }
        if (!namesShadedType) return false;
        return text.indexOf('(') >= 0 || text.endsWith(";");
    }

    private static byte[] relocateText(byte[] bytes) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (result.length() > 0) result.append('\n');
            result.append(relocateDotted(line));
        }
        return result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The dotted rewrite, applied anywhere in a blob of text.
     *
     * Each shaded prefix is matched **with its trailing dot**, which is what
     * separates a class name from a namespace that merely shares its start.
     */
    private static byte[] relocateDottedBytes(byte[] bytes) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        String dottedPrefix = PREFIX.replace('/', '.');
        for (String shaded : SHADED) {
            String dotted = shaded.replace('/', '.');
            StringBuilder out = new StringBuilder(text.length() + 64);
            int from = 0;
            while (true) {
                int at = text.indexOf(dotted, from);
                if (at < 0) {
                    out.append(text, from, text.length());
                    break;
                }
                boolean done = at >= dottedPrefix.length()
                    && text.startsWith(dottedPrefix, at - dottedPrefix.length());
                out.append(text, from, at);
                if (!done) out.append(dottedPrefix);
                out.append(dotted);
                from = at + dotted.length();
            }
            text = out.toString();
        }
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The same mapping for names written with dots rather than slashes. */
    private static String relocateDotted(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return name;
        if (trimmed.startsWith(PREFIX.replace('/', '.'))) return name;
        for (String shaded : SHADED) {
            String dotted = shaded.replace('/', '.');
            if (trimmed.startsWith(dotted)) {
                return name.replace(trimmed, PREFIX.replace('/', '.') + trimmed);
            }
        }
        return name;
    }
}
