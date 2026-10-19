package com.arktosbrowser.mobile.utils

/**
 * A collection of utils methods for
 * [StringBuilder] that provides
 * API equality with the [String]
 * API.
 */
object StringBuilderUtils {

    private const val SPACE = " "
    private const val EMPTY = ""

    /**
     * Replace the first string found in a
     * string builder with another string.
     *
     * @param stringBuilder the string builder.
     * @param toReplace     the string to replace.
     * @param replacement   the replacement string.
     */
    fun replace(
        stringBuilder: StringBuilder,
        toReplace: String,
        replacement: String
    ) {
        val index = stringBuilder.indexOf(toReplace)
        if (index >= 0) {
            stringBuilder.replace(index, index + toReplace.length, replacement)
        }
    }

    /**
     * Trims a string builder of
     * any spaces at the beginning
     * and end.
     *
     * @param stringBuilder the string builder.
     */
    fun trim(stringBuilder: StringBuilder) {
        while (stringBuilder.indexOf(SPACE) == 0) {
            stringBuilder.replace(0, 1, EMPTY)
        }

        while (stringBuilder.lastIndexOf(SPACE) == stringBuilder.length - 1 && stringBuilder.isNotEmpty()) {
            stringBuilder.replace(stringBuilder.length - 1, stringBuilder.length, EMPTY)
        }
    }

    /**
     * Determines if the string builder is empty.
     *
     * @param stringBuilder the string builder.
     * @return true if the string builder is empty,
     * false otherwise.
     */
    fun isEmpty(stringBuilder: StringBuilder): Boolean = stringBuilder.isEmpty()

    /**
     * Determines if a string builder starts with
     * a specific string.
     *
     * @param stringBuilder the string builder.
     * @param start         the starting string.
     * @return true if the string builder starts
     * with the string, false otherwise.
     */
    fun startsWith(stringBuilder: StringBuilder, start: String): Boolean =
        stringBuilder.indexOf(start) == 0

    /**
     * Determines if a string builder contains a string.
     *
     * @param stringBuilder the string builder.
     * @param contains      the string that it might contain.
     * @return true if the string builder contains the
     * string, false otherwise.
     */
    fun contains(stringBuilder: StringBuilder, contains: String): Boolean =
        stringBuilder.indexOf(contains) >= 0

    /**
     * Determines equality between a string
     * builder and a string.
     *
     * @param stringBuilder the string builder.
     * @param equal         the string.
     * @return true if the string represented by
     * the string builder is equal to the string.
     */
    fun equals(stringBuilder: StringBuilder, equal: String): Boolean {
        val builderLength = stringBuilder.length
        if (builderLength != equal.length) {
            return false
        }

        for (n in 0 until builderLength) {
            if (stringBuilder[n] != equal[n]) {
                return false
            }
        }

        return true
    }

    /**
     * Creates a sub-string builder from the
     * current string builder.
     *
     * @param stringBuilder the string builder.
     * @param start         the starting index.
     * @param end           the ending index.
     * @return a string builder that contains the
     * characters between the indices.
     */
    fun substring(stringBuilder: StringBuilder, start: Int, end: Int): StringBuilder {
        val newStringBuilder = StringBuilder(stringBuilder)
        newStringBuilder.replace(end, stringBuilder.length, EMPTY)
        newStringBuilder.replace(0, start, EMPTY)

        return newStringBuilder
    }
}
