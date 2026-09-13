package com.osamu.aide.debugger

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reply a debugger needs most and mis-reads most easily.
 *
 * Built here from the debug information `dexdump` prints for a method this
 * project's own pipeline compiled, so the fixture is a real VM's answer rather
 * than an invention:
 *
 * ```
 * locals :
 *   0x0002 - 0x001b reg=0 accumulatedTotal I
 *   0x0000 - 0x001b reg=3 this Lcom/example/debugdemo/Ticker;
 *   0x0000 - 0x001b reg=4 counter I
 * ```
 *
 * The failure this exists to prevent is not a crash. A `VariableTable` read at
 * the wrong offsets yields entries that look almost right -- a real name beside
 * the wrong slot -- and reading a frame with them returns another variable's
 * value under this one's name, which is worse than no debugger at all.
 */
class JdwpVariableTableTest {

    private val sizes = IdSizes(8, 8, 8, 8, 8)

    /** Encodes a reply the way the spec orders it. */
    private fun reply(
        argCnt: Int,
        entries: List<VariableInfo>,
    ): PacketReader {
        val writer = PacketWriter(sizes).int(argCnt).int(entries.size)
        entries.forEach {
            writer.long(it.codeIndex)
                .string(it.name)
                .string(it.signature)
                .int(it.length)
                .int(it.slot)
        }
        return PacketReader(writer.build(), sizes)
    }

    private val ticker = listOf(
        VariableInfo("accumulatedTotal", "I", slot = 0, codeIndex = 2, length = 25),
        VariableInfo("this", "Lcom/example/debugdemo/Ticker;", slot = 3, codeIndex = 0, length = 27),
        VariableInfo("counter", "I", slot = 4, codeIndex = 0, length = 27),
    )

    @Test
    fun every_field_of_every_entry_comes_back_where_it_went_in() {
        assertEquals(ticker, parseVariableTable(reply(argCnt = 2, entries = ticker)))
    }

    /**
     * Every entry is read, not one fewer.
     *
     * `argCnt` and `slots` are adjacent integers and reading them in the wrong
     * order costs exactly one entry -- the one a user is usually looking for,
     * since the arguments come first.
     */
    @Test
    fun the_entry_count_comes_from_slots_and_not_from_argcnt() {
        assertEquals(3, parseVariableTable(reply(argCnt = 2, entries = ticker)).size)
    }

    @Test
    fun a_method_with_no_locals_reads_as_empty() {
        assertEquals(emptyList<VariableInfo>(), parseVariableTable(reply(0, emptyList())))
    }
}
