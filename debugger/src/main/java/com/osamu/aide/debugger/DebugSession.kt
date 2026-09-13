package com.osamu.aide.debugger

import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/** A method as the VM describes it. */
data class MethodInfo(
    val id: Long,
    val name: String,
    /** JVM descriptor, e.g. `(I)I`. Kept because overloads share a name. */
    val signature: String,
    val modifiers: Int,
)

/** One entry of a method's line table: which code index a source line starts at. */
data class LineEntry(val codeIndex: Long, val lineNumber: Int)

/** A local variable's slot, and the range of code over which it holds it. */
data class VariableInfo(
    val name: String,
    val signature: String,
    val slot: Int,
    val codeIndex: Long,
    val length: Int,
) {
    /** Whether this variable is live at [index], which is when it can be read. */
    fun isLiveAt(index: Long): Boolean = index >= codeIndex && index < codeIndex + length
}

/** One frame of a stopped thread's stack. */
data class Frame(val id: Long, val location: Location)

/** A value read out of the debuggee. */
sealed interface JdwpValue {

    val tag: Int

    /** A primitive. [value] is the boxed Kotlin equivalent of the JDWP tag. */
    data class Primitive(override val tag: Int, val value: Any) : JdwpValue

    /**
     * An object, by identity.
     *
     * [id] is zero for null, which is how the wire says it -- there is no null
     * tag. Reading anything further about it costs another round trip, which is
     * why this is a reference and not a rendered string.
     */
    data class Reference(override val tag: Int, val id: Long) : JdwpValue {
        val isNull: Boolean get() = id == 0L
    }

    data object Void : JdwpValue {
        override val tag: Int get() = Jdwp.Tag.VOID
    }
}

/**
 * A debugging session with one running app.
 *
 * Everything here is one JDWP command or a short sequence of them, named for
 * what a debugger is trying to do rather than for the packet it sends.
 * [breakpointAt] is the one that is worth reading: it is four round trips, and
 * it is the sequence that a line number in an editor actually costs.
 *
 * **The suspend state is the caller's to track.** JDWP has no query for "is the
 * VM running"; it has commands that fail with `THREAD_NOT_SUSPENDED` when it
 * is. Every method here that requires a stopped thread says so, because the
 * error arrives as a number and the cause is always several steps earlier.
 */
class DebugSession(private val connection: JdwpConnection) : Closeable {

    /** What the VM reported about itself at attach. */
    val version: VmVersion get() = connection.version

    /** Everything the VM reports without being asked -- breakpoints, and death. */
    val events: Flow<JdwpEventSet> get() = connection.events

    /**
     * Finds a loaded class by its JVM signature, e.g. `Lcom/example/Main;`.
     *
     * Empty when the class has not been loaded *yet*, which is not the same as
     * absent and is the usual state early in an app's life. A debugger that
     * wants to break in a class before it loads has to ask for a
     * `CLASS_PREPARE` event instead of retrying this.
     */
    suspend fun classesBySignature(signature: String): List<ClassRef> {
        val body = connection.writer().string(signature).build()
        val reply = connection.request(
            Jdwp.VirtualMachine.SET,
            Jdwp.VirtualMachine.CLASSES_BY_SIGNATURE,
            body,
        )
        return (0 until reply.int()).map {
            ClassRef(
                typeTag = reply.byte(),
                typeId = reply.referenceTypeId(),
                status = reply.int(),
            )
        }
    }

    suspend fun methods(typeId: Long): List<MethodInfo> {
        val body = connection.writer().referenceTypeId(typeId).build()
        val reply = connection.request(Jdwp.ReferenceType.SET, Jdwp.ReferenceType.METHODS, body)
        return (0 until reply.int()).map {
            MethodInfo(
                id = reply.methodId(),
                name = reply.string(),
                signature = reply.string(),
                modifiers = reply.int(),
            )
        }
    }

    /**
     * Where each source line of a method begins.
     *
     * Entries are not sorted by the spec and dex does not order them either, so
     * a caller looking for "the code index for line N" must not assume the
     * first match is the lowest.
     */
    suspend fun lineTable(typeId: Long, methodId: Long): List<LineEntry> {
        val body = connection.writer().referenceTypeId(typeId).methodId(methodId).build()
        val reply = connection.request(Jdwp.Method.SET, Jdwp.Method.LINE_TABLE, body)
        reply.long() // lowest code index
        reply.long() // highest code index
        return (0 until reply.int()).map { LineEntry(reply.long(), reply.int()) }
    }

    /**
     * The local variables of a method, with the slots their values live in.
     *
     * **Absent unless the app was built with debug information**, and the VM
     * says so with `ABSENT_INFORMATION` (error 101) rather than an empty table.
     * A release build reaches here and fails exactly that way, which is worth
     * knowing before blaming the frame.
     */
    suspend fun variableTable(typeId: Long, methodId: Long): List<VariableInfo> {
        val body = connection.writer().referenceTypeId(typeId).methodId(methodId).build()
        return parseVariableTable(
            connection.request(Jdwp.Method.SET, Jdwp.Method.VARIABLE_TABLE, body),
        )
    }

    /**
     * Sets a breakpoint at a source line, and returns the request to clear it
     * with.
     *
     * Four round trips: find the class, list its methods, read each candidate's
     * line table, then register. That cost is why a UI should set breakpoints
     * when they are placed rather than when Run is pressed.
     *
     * Null when the line has no code -- a blank line, a comment, or a line the
     * compiler folded away. **Returning null rather than a breakpoint that
     * never fires** is the whole point: silently attaching to the nearest line
     * is how a debugger appears to ignore a breakpoint.
     */
    suspend fun breakpointAt(
        classSignature: String,
        lineNumber: Int,
        suspendPolicy: Int = Jdwp.SuspendPolicy.EVENT_THREAD,
    ): Breakpoint? {
        val classRef = classesBySignature(classSignature).firstOrNull() ?: return null
        return breakpointInType(classRef.typeTag, classRef.typeId, lineNumber, suspendPolicy)
    }

    /**
     * Sets a breakpoint at a line of a class already identified by id.
     *
     * The half of [breakpointAt] that does not look the class up, because a
     * deferred breakpoint already has the id: it arrives in the
     * [JdwpEvent.ClassPrepare] that says the class exists, and looking it up
     * again by signature is a round trip spent learning what the event said.
     */
    suspend fun breakpointInType(
        typeTag: Int,
        typeId: Long,
        lineNumber: Int,
        suspendPolicy: Int = Jdwp.SuspendPolicy.EVENT_THREAD,
    ): Breakpoint? {
        for (method in methods(typeId)) {
            val index = runCatching { lineTable(typeId, method.id) }
                .getOrDefault(emptyList())
                .filter { it.lineNumber == lineNumber }
                .minByOrNull { it.codeIndex }
                ?.codeIndex
                ?: continue
            val location = Location(typeTag, typeId, method.id, index)
            return Breakpoint(setBreakpoint(location, suspendPolicy), location, method)
        }
        return null
    }

    /**
     * Asks to be told when a class is prepared, **and to have the loading
     * thread held there**.
     *
     * This is how a breakpoint is placed in a class that does not exist yet,
     * which early in an app's life is most of them. [classesBySignature]
     * answers nothing for such a class, and polling it would lose the race:
     * by the time a poll sees the class, the code the breakpoint was for may
     * already have run.
     *
     * **The suspend policy is the point, not a default.** With `EVENT_THREAD`
     * the thread that caused the load is stopped before any of the class's
     * code runs, so a breakpoint installed in response is guaranteed to be in
     * place first. With `NONE` it is a race that the debugger usually wins on
     * an emulator and loses on a slow phone. The caller must
     * [resumeThread] once its breakpoints are in, or the app stays frozen.
     *
     * [qualifiedName] is a class name with dots, as `ClassMatch` expects; a
     * leading or trailing `*` is a wildcard, which is also how inner classes
     * (`Outer$*`) are caught.
     */
    suspend fun requestClassPrepare(
        qualifiedName: String,
        suspendPolicy: Int = Jdwp.SuspendPolicy.EVENT_THREAD,
    ): Int {
        val body = connection.writer()
            .byte(Jdwp.EventKind.CLASS_PREPARE)
            .byte(suspendPolicy)
            .int(1) // one modifier
            .byte(Jdwp.Modifier.CLASS_MATCH)
            .string(qualifiedName)
            .build()
        return connection.request(
            Jdwp.EventRequest.SET,
            Jdwp.EventRequest.SET_REQUEST,
            body,
        ).int()
    }

    suspend fun clearClassPrepare(requestId: Int) {
        val body = connection.writer()
            .byte(Jdwp.EventKind.CLASS_PREPARE)
            .int(requestId)
            .build()
        connection.request(Jdwp.EventRequest.SET, Jdwp.EventRequest.CLEAR, body)
    }

    /** Registers a breakpoint at an exact location. Returns its request id. */
    suspend fun setBreakpoint(
        location: Location,
        suspendPolicy: Int = Jdwp.SuspendPolicy.EVENT_THREAD,
    ): Int {
        val body = connection.writer()
            .byte(Jdwp.EventKind.BREAKPOINT)
            .byte(suspendPolicy)
            .int(1) // one modifier
            .byte(Jdwp.Modifier.LOCATION_ONLY)
            .location(location)
            .build()
        return connection.request(
            Jdwp.EventRequest.SET,
            Jdwp.EventRequest.SET_REQUEST,
            body,
        ).int()
    }

    suspend fun clearBreakpoint(requestId: Int) {
        val body = connection.writer()
            .byte(Jdwp.EventKind.BREAKPOINT)
            .int(requestId)
            .build()
        connection.request(Jdwp.EventRequest.SET, Jdwp.EventRequest.CLEAR, body)
    }

    /**
     * Asks the VM to stop the next time [threadId] reaches a new line.
     *
     * **Registered while the thread is suspended, and it does not resume it.**
     * A step is two halves -- ask, then let go -- and they are separate here
     * because the caller owns the event loop: [requestStep], then
     * [resumeThread], then wait for the [JdwpEvent.SingleStep] that comes back.
     *
     * **The request must be cleared once it fires.** A step request is not
     * self-cancelling: leave it registered and the thread stops again on the
     * next line, and the line after that, which reads as the app running at one
     * frame a second rather than as a leaked request. [clearStep] is not
     * optional politeness.
     */
    suspend fun requestStep(
        threadId: Long,
        depth: Int = Jdwp.StepDepth.OVER,
        size: Int = Jdwp.StepSize.LINE,
        suspendPolicy: Int = Jdwp.SuspendPolicy.EVENT_THREAD,
    ): Int {
        val body = connection.writer()
            .byte(Jdwp.EventKind.SINGLE_STEP)
            .byte(suspendPolicy)
            .int(1) // one modifier
            .byte(Jdwp.Modifier.STEP)
            .objectId(threadId)
            .int(size)
            .int(depth)
            .build()
        return connection.request(
            Jdwp.EventRequest.SET,
            Jdwp.EventRequest.SET_REQUEST,
            body,
        ).int()
    }

    /** Cancels a step request. Required after it fires; see [requestStep]. */
    suspend fun clearStep(requestId: Int) {
        val body = connection.writer()
            .byte(Jdwp.EventKind.SINGLE_STEP)
            .int(requestId)
            .build()
        connection.request(Jdwp.EventRequest.SET, Jdwp.EventRequest.CLEAR, body)
    }

    /**
     * The stack of a **suspended** thread, innermost frame first.
     *
     * Fails with `THREAD_NOT_SUSPENDED` otherwise, and that is the right
     * behaviour: a stack read from a running thread would be a snapshot of
     * something that had already moved.
     */
    suspend fun frames(threadId: Long): List<Frame> {
        val body = connection.writer()
            .objectId(threadId)
            .int(0) // from the top
            .int(-1) // all of them
            .build()
        val reply = connection.request(
            Jdwp.ThreadReference.SET,
            Jdwp.ThreadReference.FRAMES,
            body,
        )
        return (0 until reply.int()).map { Frame(reply.frameId(), reply.location()) }
    }

    /**
     * Reads local variables out of one frame.
     *
     * The signature's first character has to be sent with each slot: the VM
     * uses it to decide how wide the value is, and sending the wrong one
     * returns a value read at the wrong width rather than an error.
     */
    suspend fun values(
        threadId: Long,
        frameId: Long,
        variables: List<VariableInfo>,
    ): Map<String, JdwpValue> {
        if (variables.isEmpty()) return emptyMap()
        val writer = connection.writer()
            .objectId(threadId)
            .frameId(frameId)
            .int(variables.size)
        variables.forEach { writer.int(it.slot).byte(it.signature.first().code) }
        val reply = connection.request(
            Jdwp.StackFrame.SET,
            Jdwp.StackFrame.GET_VALUES,
            writer.build(),
        )
        val count = reply.int()
        return (0 until count).associate { index ->
            variables[index].name to reply.value()
        }
    }

    /** The characters behind a string reference, which is one more round trip. */
    suspend fun stringValue(objectId: Long): String {
        val body = connection.writer().objectId(objectId).build()
        return connection.request(STRING_REFERENCE_SET, STRING_REFERENCE_VALUE, body).string()
    }

    /**
     * The instance fields of an object, with their current values.
     *
     * Three round trips -- the object's runtime type, that type's fields, then
     * their values -- which is why a UI should expand an object when asked and
     * not eagerly. **Only the fields its own class declares**: `Fields` does
     * not walk superclasses, so an Activity's own state is here and
     * `Activity`'s is not. Walking the hierarchy is `ClassType.Superclass`
     * and a loop, and deliberately left to the caller who wants the cost.
     *
     * Static fields are skipped. They belong to the class rather than the
     * object, and showing them under an instance invites reading a shared
     * value as this object's own.
     */
    suspend fun fields(objectId: Long): List<FieldValue> {
        val typeReply = connection.request(
            OBJECT_REFERENCE_SET,
            OBJECT_REFERENCE_TYPE,
            connection.writer().objectId(objectId).build(),
        )
        typeReply.byte() // type tag
        val typeId = typeReply.referenceTypeId()

        val fieldsReply = connection.request(
            Jdwp.ReferenceType.SET,
            REFERENCE_TYPE_FIELDS,
            connection.writer().referenceTypeId(typeId).build(),
        )
        val declared = (0 until fieldsReply.int()).map {
            FieldInfo(
                id = fieldsReply.fieldId(),
                name = fieldsReply.string(),
                signature = fieldsReply.string(),
                modifiers = fieldsReply.int(),
            )
        }.filter { it.modifiers and ACC_STATIC == 0 }
        if (declared.isEmpty()) return emptyList()

        val writer = connection.writer().objectId(objectId).int(declared.size)
        declared.forEach { writer.fieldId(it.id) }
        val valuesReply = connection.request(
            OBJECT_REFERENCE_SET,
            OBJECT_REFERENCE_GET_VALUES,
            writer.build(),
        )
        val count = valuesReply.int()
        return (0 until count).map { index ->
            FieldValue(declared[index].name, declared[index].signature, valuesReply.value())
        }
    }

    suspend fun threadName(threadId: Long): String {
        val body = connection.writer().objectId(threadId).build()
        return connection.request(
            Jdwp.ThreadReference.SET,
            Jdwp.ThreadReference.NAME,
            body,
        ).string()
    }

    suspend fun allThreads(): List<Long> {
        val reply = connection.request(
            Jdwp.VirtualMachine.SET,
            Jdwp.VirtualMachine.ALL_THREADS,
        )
        return (0 until reply.int()).map { reply.objectId() }
    }

    /**
     * Lets a thread go after a breakpoint.
     *
     * **Which resume to call is decided by the suspend policy that stopped it**,
     * not by preference: a breakpoint registered with `EVENT_THREAD` suspended
     * one thread and [resumeThread] releases it, while `ALL` suspended
     * everything and needs [resume]. Calling the wrong one leaves the app
     * half-frozen with no error -- the command succeeds, and nothing moves.
     */
    suspend fun resumeThread(threadId: Long) {
        val body = connection.writer().objectId(threadId).build()
        connection.request(Jdwp.ThreadReference.SET, Jdwp.ThreadReference.RESUME, body)
    }

    suspend fun resume() {
        connection.request(Jdwp.VirtualMachine.SET, Jdwp.VirtualMachine.RESUME)
    }

    suspend fun suspend() {
        connection.request(Jdwp.VirtualMachine.SET, Jdwp.VirtualMachine.SUSPEND)
    }

    override fun close() = connection.close()

    /**
     * Reads a tagged value: a type byte, then a value whose width it decides.
     *
     * An unknown tag is fatal rather than skipped, because the width is
     * unknowable and everything after it in the same reply would be read at the
     * wrong offset.
     */
    private fun PacketReader.value(): JdwpValue = when (val tag = byte()) {
        Jdwp.Tag.BOOLEAN -> JdwpValue.Primitive(tag, byte() != 0)
        Jdwp.Tag.BYTE -> JdwpValue.Primitive(tag, byte().toByte())
        Jdwp.Tag.CHAR -> JdwpValue.Primitive(tag, ((byte() shl 8) or (byte() and 0xFF)).toChar())
        Jdwp.Tag.SHORT -> JdwpValue.Primitive(tag, ((byte() shl 8) or (byte() and 0xFF)).toShort())
        Jdwp.Tag.INT -> JdwpValue.Primitive(tag, int())
        Jdwp.Tag.LONG -> JdwpValue.Primitive(tag, long())
        Jdwp.Tag.FLOAT -> JdwpValue.Primitive(tag, Float.fromBits(int()))
        Jdwp.Tag.DOUBLE -> JdwpValue.Primitive(tag, Double.fromBits(long()))
        Jdwp.Tag.VOID -> JdwpValue.Void
        in OBJECT_TAGS -> JdwpValue.Reference(tag, objectId())
        else -> throw IllegalStateException(
            "unknown JDWP value tag ${tag.toChar()} ($tag); the rest of this reply is unreadable",
        )
    }

    private companion object {
        const val STRING_REFERENCE_SET = 10
        const val STRING_REFERENCE_VALUE = 1

        const val OBJECT_REFERENCE_SET = 9
        const val OBJECT_REFERENCE_TYPE = 1
        const val OBJECT_REFERENCE_GET_VALUES = 2
        const val REFERENCE_TYPE_FIELDS = 4

        /** `ACC_STATIC`, as JDWP reports field modifiers in JVM form. */
        const val ACC_STATIC = 0x0008

        /** Tags whose value on the wire is an object id. */
        val OBJECT_TAGS = setOf(
            Jdwp.Tag.OBJECT,
            Jdwp.Tag.STRING,
            Jdwp.Tag.THREAD,
            Jdwp.Tag.CLASS_OBJECT,
            Jdwp.Tag.ARRAY,
            'g'.code, // thread group
            'l'.code, // class loader
        )
    }
}

/**
 * Reads a `Method.VariableTable` reply.
 *
 * **Lifted out of [DebugSession] so a JVM test can drive it**, because it is
 * the reply whose fields are easiest to get wrong: five of them per entry, in
 * an order that is not the order anyone would declare them in, and a
 * misalignment produces entries that look almost right -- a plausible name
 * against the wrong slot -- rather than an error.
 *
 * It was suspected once and was innocent: locals came back unnamed, and the
 * cause was a debuggee still running a build from before `JavaCompileStage`
 * learned `-g`. `JdwpVariableTableTest` is what cleared it, with a fixture
 * taken from `dexdump`, and it stays so the next suspicion is settled the same
 * way. `FINDINGS.md` section 3.
 */
internal fun parseVariableTable(reply: PacketReader): List<VariableInfo> {
    reply.int() // argCnt: slots taken by arguments, all of which appear below
    val slots = reply.int()
    return (0 until slots).map {
        // Written in wire order. Kotlin evaluates named arguments in the order
        // they appear here rather than in declaration order, which is what
        // makes this legal -- but it is a fact about the language that a reader
        // should not have to recall, so the order is stated in the comment as
        // well: codeIndex, name, signature, length, slot.
        val codeIndex = reply.long()
        val name = reply.string()
        val signature = reply.string()
        val length = reply.int()
        val slot = reply.int()
        VariableInfo(
            name = name,
            signature = signature,
            slot = slot,
            codeIndex = codeIndex,
            length = length,
        )
    }
}

/** A field as its declaring class describes it. */
data class FieldInfo(val id: Long, val name: String, val signature: String, val modifiers: Int)

/** One instance field of an object, and what it currently holds. */
data class FieldValue(val name: String, val signature: String, val value: JdwpValue)

/** A loaded class, as `ClassesBySignature` reports it. */
data class ClassRef(val typeTag: Int, val typeId: Long, val status: Int)

/** A registered breakpoint: what to clear it with, and where it landed. */
data class Breakpoint(
    val requestId: Int,
    val location: Location,
    val method: MethodInfo,
)

/**
 * Attaches to a debuggee that is already listening.
 *
 * The port is the caller's problem on purpose. AIDE-OS's own debug builds open
 * one because the build template tells them to; anything else -- a Shizuku
 * helper launching a foreign app, a JVM started with `-agentlib:jdwp` -- ends
 * up in the same place, and this module does not need to know which.
 */
suspend fun attachDebugger(
    host: String = "127.0.0.1",
    port: Int,
    connectTimeoutMs: Int = 5_000,
): DebugSession = DebugSession(JdwpConnection.open(host, port, connectTimeoutMs))

/** The JVM signature of a class, which is what JDWP looks classes up by. */
fun classSignature(qualifiedName: String): String =
    "L" + qualifiedName.replace('.', '/') + ";"
