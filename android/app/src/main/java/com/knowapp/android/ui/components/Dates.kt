package com.knowapp.android.ui.components

import com.knowapp.android.ui.statements.dateOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun relativeDay(iso: String): String {
    val date = dateOf(iso)
    val today = LocalDate.now()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("d MMM"))
        else -> date.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}


// "Active today", "Active 3 Oct", "Never active": when a person or institution last did something
fun lastSeen(iso: String?): String {
    if (iso == null) return "Never active"
    val label = relativeDay(iso)
    return if (label == "Today" || label == "Yesterday") "Active ${label.lowercase()}" else "Active $label"
}

fun daysSince(iso: String?): Long? = iso?.let { java.time.temporal.ChronoUnit.DAYS.between(dateOf(it), LocalDate.now()) }

private val AMBIGUOUS = setOf('0', 'O', 'o', '1', 'l', 'I')
private const val PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

// A temporary password that is easy to read out over the phone: no look-alike characters, always a letter and a digit
fun generatePassword(length: Int = 14): String {
    val random = java.security.SecureRandom()
    val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    val lower = "abcdefghijkmnopqrstuvwxyz"
    val digits = "23456789"
    val symbols = "!#$%&*?@"
    val all = upper + lower + digits + symbols
    val chars = mutableListOf(upper[random.nextInt(upper.length)], lower[random.nextInt(lower.length)], digits[random.nextInt(digits.length)], symbols[random.nextInt(symbols.length)])
    while (chars.size < length) chars.add(all[random.nextInt(all.length)])
    chars.shuffle(random)
    return chars.joinToString("")
}

// The same rule the server enforces: 10+ characters with an uppercase letter, a lowercase letter, a number and a symbol
fun isStrongPassword(p: String): Boolean {
    val v = p.trim()
    return v.length >= 10 && v.any { it in 'A'..'Z' } && v.any { it in 'a'..'z' } && v.any { it in '0'..'9' } && v.any { !it.isLetterOrDigit() && !it.isWhitespace() }
}

const val PASSWORD_RULE_HINT = "At least 10 characters with an uppercase letter, a lowercase letter, a number and a symbol"
