package com.github739c1ae2.focuslock.util

import java.text.Collator
import java.util.Locale

fun <T> Iterable<T>.sortTextBy(selector: (T) -> String): List<T> {
    val collator = Collator.getInstance(Locale.getDefault())
    return sortedWith(compareBy(collator, selector))
}