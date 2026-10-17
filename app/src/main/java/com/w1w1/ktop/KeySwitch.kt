package com.w1w1.ktop

/**
 * Shifter button assignment, decoded from the RD's `KeySwitch` value in the
 * startRead/read device-info dump (read-only - setting it needs
 * switchFingerOrder 0x9C, whose payload is not known yet).
 *
 * eds-ox PROTOCOL-APP.md:
 *  - OX (one shifter, 1 digit): 0 = standard, 1 = buttons swapped
 *  - TX / GeX (3+ digits): digit 1 = right small button, digit 2 = right big
 *    button, digit 3 = left button; 1 = to smaller cog (up shift),
 *    2 = to larger cog (down shift), 3 = front shift.
 *    GeX reports a 4th digit (e.g. "2120") whose meaning is unknown; it is
 *    ignored here and only visible in the raw value.
 */
object KeySwitch {

    enum class Button { RIGHT_SMALL, RIGHT_BIG, LEFT }

    enum class Function { UP, DOWN, FRONT, UNKNOWN }

    sealed class Assignment {
        abstract val raw: String

        /** OX: one shifter, standard or swapped order. */
        data class SingleShifter(override val raw: String, val swapped: Boolean) : Assignment()

        /** TX / GeX: function per button, in Button order. */
        data class PerButton(override val raw: String, val buttons: Map<Button, Function>) : Assignment()

        /** Format not understood - show the raw value only. */
        data class Unknown(override val raw: String) : Assignment()
    }

    fun decode(raw: String?): Assignment? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty() || !v.all { it.isDigit() }) return null
        return when {
            v == "0" -> Assignment.SingleShifter(v, swapped = false)
            v == "1" -> Assignment.SingleShifter(v, swapped = true)
            v.length >= 3 -> Assignment.PerButton(v, Button.values().mapIndexed { i, b -> b to function(v[i]) }.toMap())
            else -> Assignment.Unknown(v)
        }
    }

    private fun function(c: Char): Function = when (c) {
        '1' -> Function.UP
        '2' -> Function.DOWN
        '3' -> Function.FRONT
        else -> Function.UNKNOWN
    }
}
