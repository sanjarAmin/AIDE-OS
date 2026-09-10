package com.osamu.aide.debugger

import java.io.ByteArrayOutputStream

/**
 * The widths the VM chose for its own identifiers.
 *
 * **Nothing after the handshake can be parsed without these.** JDWP object,
 * method, field, type and frame identifiers are variable-width by design and
 * the VM decides; a client that assumes eight bytes works on ART and
 * desynchronises on a VM that chose four, with the symptom appearing several
 * packets later as nonsense rather than as an error. `VirtualMachine.IDSizes`
 * is therefore the first command any session sends, and [JdwpConnection] will
 * not hand out a reader before it has one.
 *
 * ART answers eight for all five. That is a fact about one VM and not a licence
 * to hardcode it.
 */
data class IdSizes(
    val fieldId: Int,
    val methodId: Int,
    val objectId: Int,
    val referenceTypeId: Int,
    val frameId: Int,
) {
    companion object {
        /**
         * What to parse `IDSizes`' own reply with.
         *
         * A chicken-and-egg the spec resolves by fiat: the reply is five plain
         * four-byte integers, so it needs no sizes to read.
         */
        val UNKNOWN = IdSizes(4, 4, 4, 4, 4)
    }
}

/**
 * Reads a JDWP reply body.
 *
 * Sequential and stateful, like the wire: every value is at the offset the
 * previous one left, and there are no lengths to seek by. A reader that has
 * been asked for the wrong types produces plausible garbage rather than an
 * error, so the call sites are written to mirror the spec's field order
 * exactly.
 */
class PacketReader(private val bytes: ByteArray, private val sizes: IdSizes) {

    var offset: Int = 0
        private set

    val remaining: Int get() = bytes.size - offset

    fun byte(): Int = bytes[offset++].toInt()

    fun boolean(): Boolean = byte() != 0

    fun int(): Int = (0 until 4).fold(0) { acc, _ -> (acc shl 8) or (bytes[offset++].toInt() and 0xFF) }

    fun long(): Long =
        (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or (bytes[offset++].toLong() and 0xFF) }

    /** A JDWP string: a four-byte length, then that many UTF-8 bytes. */
    fun string(): String {
        val length = int()
        val value = String(bytes, offset, length, Charsets.UTF_8)
        offset += length
        return value
    }

    fun objectId(): Long = id(sizes.objectId)
    fun referenceTypeId(): Long = id(sizes.referenceTypeId)
    fun methodId(): Long = id(sizes.methodId)
    fun fieldId(): Long = id(sizes.fieldId)
    fun frameId(): Long = id(sizes.frameId)

    /** A location: tag, class, method, and an index into the method's code. */
    fun location(): Location = Location(
        typeTag = byte(),
        classId = referenceTypeId(),
        methodId = methodId(),
        index = long(),
    )

    /**
     * Reads an identifier of [width] bytes.
     *
     * Unsigned, and accumulated into a `Long` for that reason: an eight-byte
     * ART object id with the top bit set is negative as a signed value, and
     * comparing two of them for equality is the only thing this code does with
     * them, so the sign never matters -- but a four-byte id widened *with* sign
     * would not equal itself after a round trip.
     */
    private fun id(width: Int): Long =
        (0 until width).fold(0L) { acc, _ -> (acc shl 8) or (bytes[offset++].toLong() and 0xFF) }
}

/** Builds a JDWP command body. The mirror of [PacketReader], same order rules. */
class PacketWriter(private val sizes: IdSizes) {

    private val out = ByteArrayOutputStream()

    fun byte(value: Int) = apply { out.write(value and 0xFF) }

    fun int(value: Int) = apply {
        for (shift in 24 downTo 0 step 8) out.write((value shr shift) and 0xFF)
    }

    fun long(value: Long) = apply {
        for (shift in 56 downTo 0 step 8) out.write(((value shr shift) and 0xFF).toInt())
    }

    fun string(value: String) = apply {
        val encoded = value.toByteArray(Charsets.UTF_8)
        int(encoded.size)
        out.write(encoded)
    }

    fun objectId(value: Long) = id(value, sizes.objectId)
    fun referenceTypeId(value: Long) = id(value, sizes.referenceTypeId)
    fun methodId(value: Long) = id(value, sizes.methodId)
    fun frameId(value: Long) = id(value, sizes.frameId)

    fun location(location: Location) = apply {
        byte(location.typeTag)
        referenceTypeId(location.classId)
        methodId(location.methodId)
        long(location.index)
    }

    fun build(): ByteArray = out.toByteArray()

    private fun id(value: Long, width: Int) = apply {
        for (shift in (width - 1) * 8 downTo 0 step 8) out.write(((value shr shift) and 0xFF).toInt())
    }
}

/** Where execution is: a method, and how far into its bytecode. */
data class Location(
    val typeTag: Int,
    val classId: Long,
    val methodId: Long,
    /** The code index. A `long` by spec, even though dex indices are small. */
    val index: Long,
)

/**
 * The protocol's constants, named.
 *
 * Written out rather than passed as literals at the call sites because a
 * command set and command are two adjacent bytes with no redundancy: `15, 1` is
 * `EventRequest.Set` and `15, 2` is `EventRequest.Clear`, and transposing them
 * produces a valid packet that does the wrong thing.
 */
object Jdwp {

    val HANDSHAKE: ByteArray = "JDWP-Handshake".toByteArray(Charsets.US_ASCII)

    /**
     * Header length, which the packet's own length field **includes**.
     *
     * The single most expensive off-by-eleven available here: reading `length`
     * bytes of body leaves the next packet's header in the stream, and every
     * parse after that is garbage that reads as a protocol bug.
     */
    const val HEADER_BYTES = 11

    const val REPLY_FLAG = 0x80

    // Command sets, and the commands used. Numbers are from the JDWP spec.
    object VirtualMachine {
        const val SET = 1
        const val VERSION = 1
        const val CLASSES_BY_SIGNATURE = 2
        const val ALL_THREADS = 4
        const val ID_SIZES = 7
        const val SUSPEND = 8
        const val RESUME = 9
    }

    object ReferenceType {
        const val SET = 2
        const val METHODS = 5
    }

    object Method {
        const val SET = 6
        const val LINE_TABLE = 1
        const val VARIABLE_TABLE = 2
    }

    object ThreadReference {
        const val SET = 11
        const val NAME = 1
        const val RESUME = 3
        const val FRAMES = 6
    }

    object EventRequest {
        const val SET = 15
        const val SET_REQUEST = 1
        const val CLEAR = 2
    }

    object StackFrame {
        const val SET = 16
        const val GET_VALUES = 1
    }

    /** Event kinds, of which this client asks for one. */
    object EventKind {
        const val BREAKPOINT = 2
        const val CLASS_PREPARE = 8
        const val VM_DEATH = 99
    }

    /** What a triggered event stops. */
    object SuspendPolicy {
        const val NONE = 0
        const val EVENT_THREAD = 1
        const val ALL = 2
    }

    /** Type tags, as they appear in a location and in a tagged value. */
    object Tag {
        const val CLASS = 1
        const val BYTE = 'B'.code
        const val CHAR = 'C'.code
        const val OBJECT = 'L'.code
        const val FLOAT = 'F'.code
        const val DOUBLE = 'D'.code
        const val INT = 'I'.code
        const val LONG = 'J'.code
        const val SHORT = 'S'.code
        const val VOID = 'V'.code
        const val BOOLEAN = 'Z'.code
        const val STRING = 's'.code
        const val THREAD = 't'.code
        const val CLASS_OBJECT = 'c'.code
        const val ARRAY = '['.code
    }
}

/**
 * A JDWP error the VM returned, carrying the code it sent.
 *
 * The code is the whole diagnosis and there is no message on the wire, so it is
 * kept rather than folded into a string: 13 is `THREAD_NOT_SUSPENDED`, which
 * means the caller forgot to suspend, and 20 is `INVALID_OBJECT`, which usually
 * means an id outlived the resume that invalidated it. Those are different
 * bugs, and a client that only knows "it failed" cannot tell them apart.
 */
class JdwpErrorException(
    val code: Int,
    val commandSet: Int,
    val command: Int,
) : Exception("JDWP command $commandSet/$command failed with error $code")
