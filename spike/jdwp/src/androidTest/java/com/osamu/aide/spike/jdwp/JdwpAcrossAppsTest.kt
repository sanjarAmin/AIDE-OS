package com.osamu.aide.spike.jdwp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The question the whole spike exists to answer: **can this app debug a
 * different app?**
 *
 * `:spike:jdwpdebuggee` is a separate package with a separate UID that attaches
 * the platform's `libjdwp.so` to itself over `dt_socket`. This connects to it
 * from here and speaks JDWP. Everything earlier in this spike was one process
 * talking to itself, which settles nothing about the shape `:debugger` needs.
 *
 * **A handshake is not enough to claim this works.** A TCP accept proves a
 * listener; it does not prove the peer will answer a debugger. So this sends
 * `VirtualMachine.Version` and `VirtualMachine.IDSizes` and reads the replies,
 * because those are the two commands a real client sends first -- Version to
 * know what it is talking to, IDSizes because **every later packet is
 * unparseable without it**: object and reference IDs are variable-width and the
 * VM decides the width.
 *
 * Skipped, not failed, when the debuggee is not installed:
 *
 *     ./gradlew :spike:jdwpdebuggee:assembleDebug
 *     adb install -r -t spike/jdwpdebuggee/build/outputs/apk/debug/jdwpdebuggee-debug.apk
 *     adb shell am start -n com.osamu.aide.spike.jdwpdebuggee/.DebuggeeActivity
 *
 * It is installed and started by hand on purpose: a `connectedAndroidTest` run
 * uninstalls what it installed, and a debuggee that disappears between suites
 * reads as the transport having closed.
 */
@RunWith(AndroidJUnit4::class)
class JdwpAcrossAppsTest {

    private fun connect(): Socket? = runCatching {
        Socket().apply {
            connect(InetSocketAddress("127.0.0.1", PORT), 2_000)
            soTimeout = 5_000
        }
    }.getOrElse { if (it is ConnectException) null else throw it }

    @Test
    fun this_app_speaks_jdwp_to_a_different_app() {
        val socket = connect()
        assumeTrue(
            "no debuggee on 127.0.0.1:$PORT -- see this class's comment for how to start one",
            socket != null,
        )

        socket!!.use {
            val out = DataOutputStream(it.getOutputStream())
            val input = DataInputStream(it.getInputStream())

            out.write(HANDSHAKE)
            out.flush()
            val echoed = ByteArray(HANDSHAKE.size).also(input::readFully)
            assertEquals(
                "the peer did not echo the JDWP handshake",
                String(HANDSHAKE, Charsets.US_ASCII),
                String(echoed, Charsets.US_ASCII),
            )

            // VirtualMachine.Version -- command set 1, command 1.
            val version = command(out, input, id = 1, set = 1, command = 1)
            val description = version.readJdwpString()
            val jdwpMajor = version.readInt()
            val jdwpMinor = version.readInt()
            val vmVersion = version.readJdwpString()
            val vmName = version.readJdwpString()

            // VirtualMachine.IDSizes -- command set 1, command 7.
            val sizes = command(out, input, id = 2, set = 1, command = 7)
            val fieldId = sizes.readInt()
            val methodId = sizes.readInt()
            val objectId = sizes.readInt()
            val referenceTypeId = sizes.readInt()
            val frameId = sizes.readInt()

            val report = "description=$description jdwp=$jdwpMajor.$jdwpMinor " +
                "vmVersion=$vmVersion vmName=$vmName " +
                "idSizes(field=$fieldId method=$methodId object=$objectId " +
                "refType=$referenceTypeId frame=$frameId)"
            android.util.Log.w(TAG, "cross-app JDWP -> $report")

            // ART's JDWP reports itself as a 1.6-era VM; what matters is that
            // the numbers are a VM's and not zeroes from a mis-framed read.
            assertTrue("implausible JDWP version in: $report", jdwpMajor >= 1)
            assertTrue("empty VM name in: $report", vmName.isNotBlank())
            // Widths are what every later packet is parsed with, so a wrong one
            // is not a cosmetic failure -- it desynchronises the stream.
            listOf(fieldId, methodId, objectId, referenceTypeId, frameId).forEach { width ->
                assertTrue("implausible ID width in: $report", width == 4 || width == 8)
            }
        }
    }

    /**
     * Sends one command packet and returns its reply's data.
     *
     * The header is 11 bytes and its length field **includes the header**,
     * which is the detail that costs an afternoon: reading `length` bytes of
     * body leaves 11 bytes of the next packet in the stream and every
     * subsequent parse is garbage.
     */
    private fun command(
        out: DataOutputStream,
        input: DataInputStream,
        id: Int,
        set: Int,
        command: Int,
        data: ByteArray = ByteArray(0),
    ): DataInputStream {
        out.writeInt(HEADER_BYTES + data.size)
        out.writeInt(id)
        out.writeByte(0) // flags: a command, not a reply
        out.writeByte(set)
        out.writeByte(command)
        out.write(data)
        out.flush()

        // The debuggee is running, so an event packet can arrive before the
        // reply. Replies carry the reply flag and the id we sent; anything else
        // is skipped rather than mistaken for one.
        while (true) {
            val length = input.readInt()
            val replyTo = input.readInt()
            val flags = input.readUnsignedByte()
            val error = if (flags and REPLY_FLAG != 0) {
                input.readUnsignedShort()
            } else {
                input.readUnsignedByte(); input.readUnsignedByte(); -1
            }
            val body = ByteArray(length - HEADER_BYTES).also(input::readFully)
            if (flags and REPLY_FLAG != 0 && replyTo == id) {
                assertEquals("JDWP error for command $set/$command", 0, error)
                return DataInputStream(body.inputStream())
            }
        }
    }

    /** A JDWP string: a four-byte length and then that many UTF-8 bytes. */
    private fun DataInputStream.readJdwpString(): String =
        String(ByteArray(readInt()).also(::readFully), Charsets.UTF_8)

    private companion object {
        const val TAG = "JdwpSpike"
        const val PORT = 8700
        const val HEADER_BYTES = 11
        const val REPLY_FLAG = 0x80
        val HANDSHAKE = "JDWP-Handshake".toByteArray(Charsets.US_ASCII)
    }
}
