package com.osamu.aide.lsp.nativelsp

import com.osamu.aide.toolchain.nativetools.ClangToolchain
import com.osamu.aide.toolchain.nativetools.NativeLanguage
import java.io.File

/**
 * Writes the `compile_flags.txt` clangd reads.
 *
 * **This is what replaces `--query-driver`.** clangd's usual way of learning
 * where the system headers are is to execute the compiler and ask it, and
 * nothing in app-private storage may be executed -- so the answer is supplied
 * instead. `--query-driver` is off unless requested, so the only thing needed
 * is to write the flags down.
 *
 * The flags come from [ClangToolchain.relocationFlags] rather than being
 * repeated here. There is one correct set and it is already stated once; a
 * second copy is a copy that goes stale, and the failure it produces --
 * errors inside every system header -- reads like a broken sysroot rather than
 * a stale list. `tools/clang/FINDINGS.md` §1 and §9.
 *
 * One flag per line, which is the format: clangd splits on newlines and passes
 * each line as one argument, so `-resource-dir` and its path are separate
 * lines and stay separate arguments.
 *
 * No `-x` is written. Forcing a language would make clangd treat every `.c` as
 * C++; without it clangd infers from the extension, which is right for
 * everything except a bare header, and guessing wrong about a header costs a
 * few spurious diagnostics rather than a wrong compile.
 */
internal object CompileFlags {

    const val FILE_NAME = "compile_flags.txt"

    /**
     * Writes the file into [directory], returning it.
     *
     * Rewritten every time the service starts rather than cached: the toolchain
     * can be reinstalled at a different path, and a stale file points clangd at
     * a directory that no longer exists.
     */
    fun write(directory: File, clang: ClangToolchain): File {
        directory.mkdirs()
        val target = File(directory, FILE_NAME)
        target.writeText(clang.relocationFlags(NativeLanguage.CXX).joinToString("\n") + "\n")
        return target
    }
}
