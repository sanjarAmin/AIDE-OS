package com.osamu.aide.engine.fast

import android.content.Context
import android.os.Build
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.toolchain.nativetools.ClangToolchain
import com.osamu.aide.toolchain.nativetools.LinkerLaunch
import com.osamu.aide.toolchain.nativetools.NativeToolRunner
import com.osamu.aide.toolchain.nativetools.NativeToolchain
import java.io.File

/**
 * Finds the C/C++ toolchain, if this device has one.
 *
 * The same arrangement as [KotlinToolchainProvider], and absence means the same
 * thing: an ordinary state, not a misconfiguration. Most projects have no
 * native code and never need 551 MB of clang.
 *
 * **The ABI is part of the identity.** A toolchain is built for one
 * architecture and its component id carries that, so a device that somehow held
 * both cannot use the wrong one, and a 32-bit device is told the toolchain does
 * not exist for it rather than handed one that cannot run.
 */
class NativeToolchainProvider(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val abi: String = Build.SUPPORTED_ABIS.first(),
) {

    /** Null when no toolchain is installed for this ABI. */
    fun toolchain(): ClangToolchain? {
        val toolchain = ClangToolchain(
            root = File(installDirectory(), "usr"),
            abi = abi,
            launch = LinkerLaunch.forThisProcess(),
            runner = NativeToolRunner(NativeToolchain.from(context), dispatchers),
        )
        return toolchain.takeIf { it.isInstalled }
    }

    /**
     * Where `:toolchain:manager` installs it.
     *
     * Derived rather than shared, for the reason [KotlinToolchainProvider] gives:
     * the engine compiles with what it is given and does not know how components
     * arrive. A test asserts this id against the manager's.
     */
    fun installDirectory(): File =
        File(context.filesDir, "toolchains/${componentId()}")

    fun componentId(): String = "$COMPONENT_PREFIX-$abi"

    companion object {
        /** Must match `ToolchainComponent.nativeToolchain(abi).id`; a test checks it. */
        const val COMPONENT_PREFIX = "clang-21.1.8"
    }
}
