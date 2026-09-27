package com.osamu.aide.debugger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framing arithmetic, on the JVM.
 *
 * Everything here is a width or an offset, and every bug in it presents the
 * same way on a device: a reply that parses into plausible nonsense several
 * packets after the mistake. That is the worst possible thing to debug through
 * an emulator, and none of it needs one.
 */
class JdwpWireTest {

    private val art = IdSizes(fieldId = 8, methodId = 8, objectId = 8, referenceTypeId = 8, frameId = 8)
    private val narrow = IdSizes(fieldId = 4, methodId = 4, objectId = 4, referenceTypeId = 4, frameId = 4)

    private fun roundTrip(sizes: IdSizes, write: PacketWriter.() -> Unit): PacketReader =
        PacketReader(PacketWriter(sizes).apply(write).build(), sizes)

    @Test
    fun integers_and_longs_survive_a_round_trip() {
        val reader = roundTrip(art) {
            int(0)
            int(-1)
            int(Int.MAX_VALUE)
            long(Long.MIN_VALUE)
            long(1234567890123L)
        }
        assertEquals(0, reader.int())
        assertEquals(-1, reader.int())
        assertEquals(Int.MAX_VALUE, reader.int())
        assertEquals(Long.MIN_VALUE, reader.long())
        assertEquals(1234567890123L, reader.long())
    }

    @Test
    fun a_string_is_a_length_and_then_utf8() {
        val reader = roundTrip(art) { string("Lcom/example/Ünïcode;") }
        assertEquals("Lcom/example/Ünïcode;", reader.string())
    }

    /**
     * A JDWP string's length is in **bytes, not characters**.
     *
     * Getting this wrong is invisible in ASCII and truncates the moment a
     * signature or a variable name is not, leaving the reader mid-character and
     * every field after it misaligned.
     */
    @Test
    fun a_strings_length_counts_bytes_rather_than_characters() {
        val bytes = PacketWriter(art).string("Ünïcode").build()
        assertEquals("Ünïcode".toByteArray(Charsets.UTF_8).size, PacketReader(bytes, art).int())
    }

    @Test
    fun an_id_is_written_at_the_width_the_vm_chose() {
        assertEquals(8, PacketWriter(art).objectId(1L).build().size)
        assertEquals(4, PacketWriter(narrow).objectId(1L).build().size)
    }

    /**
     * Identifiers are unsigned, and a narrow one must not come back negative.
     *
     * `0xFFFFFFFF` widened with sign is -1, which equals no id the VM will ever
     * send again -- so a breakpoint registered against it silently never
     * matches. Only comparison is ever done with these, so the sign is the
     * whole of their meaning.
     */
    @Test
    fun a_four_byte_identifier_is_read_unsigned() {
        val reader = roundTrip(narrow) { objectId(0xFFFFFFFFL) }
        assertEquals(0xFFFFFFFFL, reader.objectId())
    }

    @Test
    fun an_eight_byte_identifier_survives_its_top_bit() {
        val id = -0x7FFFFFFFFFFFFFFFL // top bit set, as an ART object id can be
        val reader = roundTrip(art) { objectId(id) }
        assertEquals(id, reader.objectId())
    }

    @Test
    fun a_location_round_trips_whole() {
        val location = Location(typeTag = 1, classId = 0x1122334455667788L, methodId = 42L, index = 7L)
        assertEquals(location, roundTrip(art) { location(location) }.location())
    }

    /**
     * Values written in order are read in order, and the reader ends empty.
     *
     * The remaining-bytes check is the point: a reader that stops short has
     * mis-sized something earlier, and the missing bytes are the only evidence
     * -- the values it did produce all look fine.
     */
    @Test
    fun a_mixed_body_leaves_nothing_over() {
        val reader = roundTrip(art) {
            byte(2)
            int(1)
            byte(7)
            location(Location(1, 99L, 100L, 4L))
        }
        assertEquals(2, reader.byte())
        assertEquals(1, reader.int())
        assertEquals(7, reader.byte())
        assertEquals(Location(1, 99L, 100L, 4L), reader.location())
        assertEquals("bytes left over", 0, reader.remaining)
    }

    @Test
    fun the_bootstrap_widths_are_four_because_id_sizes_needs_no_widths() {
        // Not a preference: IDSizes' reply is five plain four-byte integers by
        // spec, which is what makes reading it before knowing anything legal.
        assertEquals(IdSizes(4, 4, 4, 4, 4), IdSizes.UNKNOWN)
    }

    @Test
    fun a_variable_is_live_only_inside_its_range() {
        val variable = VariableInfo("counter", "I", slot = 1, codeIndex = 10, length = 20)
        assertTrue(variable.isLiveAt(10))
        assertTrue(variable.isLiveAt(29))
        assertTrue("the range is half-open", !variable.isLiveAt(30))
        assertTrue(!variable.isLiveAt(9))
    }

    @Test
    fun a_class_signature_is_the_jvm_form() {
        assertEquals("Lcom/osamu/aide/Main;", classSignature("com.osamu.aide.Main"))
    }
}
