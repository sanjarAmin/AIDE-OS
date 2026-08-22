package com.osamu.aide.engine.fast

import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Signs the packaged APK, which is what makes it installable at all.
 *
 * apksig is the same library the platform tools use, so the schemes and their
 * version gates come from it rather than from a table maintained here: it is
 * told the minimum SDK and works out which of v1 through v3 the APK needs.
 *
 * It also rewrites the archive as it signs, aligning every uncompressed entry --
 * which is why [PackageStage] does not align, and why the resource table only
 * has to be *stored* there, not placed.
 */
internal class SigningStage(private val dispatchers: DispatcherProvider) {

    suspend fun sign(
        unsigned: File,
        output: File,
        key: SigningKey,
        minSdk: Int,
    ): StageResult<File> = withContext(dispatchers.io) {
        if (!unsigned.isFile) return@withContext StageResult.failed("There is nothing to sign.")

        // KeyConfig.Jca, not the PrivateKey overload: that one is deprecated in
        // favour of this, which also admits keys held in a remote KMS.
        val signerConfig = ApkSigner.SignerConfig
            .Builder("debug", KeyConfig.Jca(key.privateKey), listOf(key.certificate))
            .build()

        runCatching {
            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(unsigned)
                .setOutputApk(output)
                .setMinSdkVersion(minSdk)
                // v1 is what a device below API 24 verifies; v2 and v3 are what
                // everything since does. apksig skips the ones the minimum SDK
                // makes unnecessary, so enabling all three costs nothing on a
                // modern floor and keeps an old floor installable.
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                // v4 is an out-of-band .idsig file for incremental install,
                // which needs a delivery mechanism we do not have.
                .setV4SigningEnabled(false)
                .setCreatedBy("AIDE-OS")
                .build()
                .sign()
        }.fold(
            onSuccess = { StageResult.ok(output) },
            onFailure = { failure ->
                // apksig writes its output incrementally, so a failure part way
                // through leaves a file that looks like an APK and is not one.
                output.delete()
                StageResult.failed(
                    "Signing failed: ${failure.message ?: failure::class.java.simpleName}",
                )
            },
        )
    }
}
