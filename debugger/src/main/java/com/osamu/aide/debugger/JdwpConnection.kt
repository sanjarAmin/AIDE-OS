package com.osamu.aide.debugger

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One event the VM reported. Only the kinds this client asks for are modelled. */
sealed interface JdwpEvent {

    val requestId: Int

    /**
     * An event that stopped a thread somewhere.
     *
     * A breakpoint and a completed step are the same three fields on the wire
     * and the same thing to a UI -- *execution is here now, and here is the
     * stack* -- so the code that reacts to them should not have to care which
     * arrived. What differs is the cleanup: a step request must be cleared
     * after it fires and a breakpoint must not be.
     */
    sealed interface Stopped : JdwpEvent {
        val threadId: Long
        val location: Location
    }

    data class Breakpoint(
        override val requestId: Int,
        override val threadId: Long,
        override val location: Location,
    ) : Stopped

    data class SingleStep(
        override val requestId: Int,
        override val threadId: Long,
        override val location: Location,
    ) : Stopped

    data class ClassPrepare(
        override val requestId: Int,
        val threadId: Long,
        val typeTag: Int,
        val typeId: Long,
        val signature: String,
    ) : JdwpEvent

    data class VmDeath(override val requestId: Int) : JdwpEvent

    /**
     * A kind this client did not ask for.
     *
     * Kept rather than dropped: **the rest of the packet cannot be parsed
     * without knowing the kind**, so an unexpected one means the remaining
     * events in the same composite are unreadable, and silently discarding it
     * would leave a session that stops responding for no visible reason.
     */
    data class Unparsed(override val requestId: Int, val kind: Int) : JdwpEvent
}

/**
 * A batch of events that arrived together, and what the VM suspended for them.
 *
 * **The suspend policy is the half that matters to a caller.** The events say
 * what happened; the policy says whether anything is still running, and getting
 * it wrong is how a debugger deadlocks -- waiting for a thread that was never
 * stopped, or leaving an app frozen after the user pressed Continue.
 */
data class JdwpEventSet(
    val suspendPolicy: Int,
    val events: List<JdwpEvent>,
)

/**
 * A live JDWP conversation with one VM.
 *
 * **Replies and events share one stream and arrive interleaved.** A debuggee is
 * running while it is being asked questions, so a breakpoint can land between a
 * command and its reply; a client that reads the next packet and assumes it is
 * the answer works until the first time it does not. So there is one reader
 * thread, replies are matched by the id they carry, and events go to
 * [events] rather than to whoever happened to be waiting.
 *
 * The reader is a plain thread rather than a coroutine because its whole life
 * is one blocking `read` on a socket, and closing the socket is how it is
 * cancelled -- which is also the only thing that reliably interrupts a blocked
 * read.
 */
class JdwpConnection private constructor(private val socket: Socket) : Closeable {

    /**
     * The VM's identifier widths, learned rather than known.
     *
     * **Mutable, and that is the lesser evil.** The obvious shape is an
     * immutable connection built once the widths are known -- but the widths
     * arrive *over* the connection, so that means reading two replies on a
     * throwaway object and handing the socket to a second one. Its streams are
     * buffered: whatever the first object read ahead sits in a buffer the
     * second one never sees, and what is lost that way is a whole packet
     * nothing will ever ask for again. Set exactly once, before any caller has
     * the connection.
     */
    @Volatile
    var sizes: IdSizes = IdSizes.UNKNOWN
        private set

    @Volatile
    var version: VmVersion = VmVersion.UNKNOWN
        private set

    private val out = DataOutputStream(socket.getOutputStream().buffered())
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Reply>>()

    // Unlimited on purpose: the reader thread must never block on a slow
    // consumer, because the packet behind the one it cannot deliver may be the
    // reply something else is waiting for -- which is a deadlock rather than a
    // dropped event.
    private val eventChannel = Channel<JdwpEventSet>(Channel.UNLIMITED)

    /** Everything the VM reported without being asked. */
    val events: Flow<JdwpEventSet> = eventChannel.receiveAsFlow()

    @Volatile
    private var closed = false

    private val reader = Thread({ readLoop() }, "jdwp-reader").apply {
        isDaemon = true
        start()
    }

    private data class Reply(val error: Int, val body: ByteArray)

    /** A fresh writer, already knowing this VM's identifier widths. */
    fun writer(): PacketWriter = PacketWriter(sizes)

    /**
     * Sends a command and waits for its reply.
     *
     * Throws [JdwpErrorException] when the VM refuses, rather than returning an
     * error code a caller can forget to check: every refusal here is a bug in
     * the command that was sent, not a condition to handle.
     */
    suspend fun request(
        commandSet: Int,
        command: Int,
        body: ByteArray = ByteArray(0),
    ): PacketReader {
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<Reply>()
        pending[id] = waiter

        synchronized(out) {
            out.writeInt(Jdwp.HEADER_BYTES + body.size)
            out.writeInt(id)
            out.writeByte(0)
            out.writeByte(commandSet)
            out.writeByte(command)
            out.write(body)
            out.flush()
        }

        val reply = try {
            waiter.await()
        } finally {
            pending.remove(id)
        }
        if (reply.error != 0) throw JdwpErrorException(reply.error, commandSet, command)
        return PacketReader(reply.body, sizes)
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val length = input.readInt()
                val id = input.readInt()
                val flags = input.readUnsignedByte()
                if (flags and Jdwp.REPLY_FLAG != 0) {
                    val error = input.readUnsignedShort()
                    val body = ByteArray(length - Jdwp.HEADER_BYTES).also(input::readFully)
                    pending.remove(id)?.complete(Reply(error, body))
                } else {
                    val commandSet = input.readUnsignedByte()
                    val command = input.readUnsignedByte()
                    val body = ByteArray(length - Jdwp.HEADER_BYTES).also(input::readFully)
                    // Command set 64 is Event, command 100 is Composite, and it
                    // is the only thing a VM sends unprompted.
                    if (commandSet == EVENT_SET && command == EVENT_COMPOSITE) {
                        eventChannel.trySend(parseEvents(body))
                    }
                }
            }
        } catch (_: EOFException) {
            // The debuggee exited. Ordinary.
        } catch (t: Throwable) {
            if (!closed) failPending(t)
        } finally {
            failPending(EOFException("the debuggee closed the connection"))
            eventChannel.close()
        }
    }

    /**
     * Wakes everyone waiting on a reply that will never come.
     *
     * Without this a dead debuggee presents as the IDE hanging: the socket is
     * gone, the reader thread has exited, and every `request` is suspended on a
     * deferred nothing will complete.
     */
    private fun failPending(cause: Throwable) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.completeExceptionally(cause)
        }
    }

    private fun parseEvents(body: ByteArray): JdwpEventSet {
        val reader = PacketReader(body, sizes)
        val policy = reader.byte()
        val count = reader.int()
        val events = ArrayList<JdwpEvent>(count)
        for (index in 0 until count) {
            val kind = reader.byte()
            val requestId = reader.int()
            val event = when (kind) {
                Jdwp.EventKind.BREAKPOINT -> JdwpEvent.Breakpoint(
                    requestId = requestId,
                    threadId = reader.objectId(),
                    location = reader.location(),
                )

                // Identical on the wire to a breakpoint, which is why they
                // share a shape above rather than being parsed twice.
                Jdwp.EventKind.SINGLE_STEP -> JdwpEvent.SingleStep(
                    requestId = requestId,
                    threadId = reader.objectId(),
                    location = reader.location(),
                )

                Jdwp.EventKind.CLASS_PREPARE -> JdwpEvent.ClassPrepare(
                    requestId = requestId,
                    threadId = reader.objectId(),
                    typeTag = reader.byte(),
                    typeId = reader.referenceTypeId(),
                    signature = reader.string(),
                ).also { reader.int() } // status, read to keep the offset right

                Jdwp.EventKind.VM_DEATH -> JdwpEvent.VmDeath(requestId)

                else -> JdwpEvent.Unparsed(requestId, kind)
            }
            events += event
            // An unknown kind leaves the reader at an offset nothing can
            // recover from, so the rest of the batch is abandoned rather than
            // read as nonsense.
            if (event is JdwpEvent.Unparsed) break
        }
        return JdwpEventSet(policy, events)
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        runCatching { reader.join(CLOSE_JOIN_MS) }
    }

    companion object {
        private const val EVENT_SET = 64
        private const val EVENT_COMPOSITE = 100
        private const val CLOSE_JOIN_MS = 500L

        /**
         * Connects, shakes hands, and learns the VM's identifier widths.
         *
         * All three, and in that order, because none of them is optional: the
         * handshake is fourteen bytes each way before any packet is legal, and
         * `IDSizes` must be the first command or nothing after it parses.
         */
        suspend fun open(
            host: String = "127.0.0.1",
            port: Int,
            connectTimeoutMs: Int = 5_000,
            readTimeoutMs: Int = 0,
        ): JdwpConnection {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            // Zero by default, deliberately: the reader thread blocks for as
            // long as the debuggee is alive and a timeout there is a closed
            // session, not a slow one. Callers that want a deadline put it on
            // the coroutine.
            socket.soTimeout = readTimeoutMs

            val handshake = try {
                socket.getOutputStream().apply { write(Jdwp.HANDSHAKE); flush() }
                ByteArray(Jdwp.HANDSHAKE.size).also {
                    DataInputStream(socket.getInputStream()).readFully(it)
                }
            } catch (t: Throwable) {
                socket.close()
                throw t
            }
            if (!handshake.contentEquals(Jdwp.HANDSHAKE)) {
                socket.close()
                throw IllegalStateException(
                    "not a JDWP peer on $host:$port: it replied " +
                        "\"${String(handshake, Charsets.US_ASCII)}\"",
                )
            }

            // Starts on UNKNOWN widths, which is safe for exactly this one
            // reply: IDSizes is five plain four-byte integers by spec, so it
            // needs no widths to read.
            val bootstrap = JdwpConnection(socket)
            return try {
                val sizes = bootstrap.request(
                    Jdwp.VirtualMachine.SET,
                    Jdwp.VirtualMachine.ID_SIZES,
                ).let {
                    IdSizes(
                        fieldId = it.int(),
                        methodId = it.int(),
                        objectId = it.int(),
                        referenceTypeId = it.int(),
                        frameId = it.int(),
                    )
                }
                val version = bootstrap.request(
                    Jdwp.VirtualMachine.SET,
                    Jdwp.VirtualMachine.VERSION,
                ).let {
                    VmVersion(
                        description = it.string(),
                        jdwpMajor = it.int(),
                        jdwpMinor = it.int(),
                        vmVersion = it.string(),
                        vmName = it.string(),
                    )
                }
                bootstrap.sizes = sizes
                bootstrap.version = version
                bootstrap
            } catch (t: Throwable) {
                bootstrap.close()
                throw t
            }
        }
    }

}

/** What the VM says it is. Reported to the user; not branched on. */
data class VmVersion(
    val description: String,
    val jdwpMajor: Int,
    val jdwpMinor: Int,
    val vmVersion: String,
    val vmName: String,
) {
    companion object {
        val UNKNOWN = VmVersion("", 0, 0, "", "")
    }
}
