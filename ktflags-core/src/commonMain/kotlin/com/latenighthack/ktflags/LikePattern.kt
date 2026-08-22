package com.latenighthack.ktflags

/**
 * Turns a literal prefix into a SQL `LIKE` pattern, escaping the wildcards.
 *
 * Without the escaping, an operator searching for the user id `50%_off` would match a great deal
 * more than they meant to -- `%` and `_` are wildcards, and user ids are arbitrary strings. The
 * escape character itself has to be escaped first, or `a\` would produce a dangling escape.
 *
 * Shared by both SQL stores so their `listSubjects` behaves identically. Pair it with an
 * `ESCAPE '\'` clause, which both SQLite and PostgreSQL apply by default for backslash.
 */
public fun likePrefixPattern(prefix: String): String = buildString(prefix.length + 8) {
    prefix.forEach { c ->
        when (c) {
            '\\', '%', '_' -> append('\\').append(c)
            else -> append(c)
        }
    }
    append('%')
}
