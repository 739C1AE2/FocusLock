package com.github739c1ae2.focuslock.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun Int.minutesToClockString(style: FormatStyle = FormatStyle.SHORT): String {
    val hours = this / 60
    val minutes = this % 60

    val formatter = DateTimeFormatter.ofLocalizedTime(style)
        .withLocale(LocalLocale.current.platformLocale)

    return LocalTime.of(hours % 24, minutes).format(formatter)
}

@Composable
fun Long.timestampMillisToClockString(style: FormatStyle = FormatStyle.SHORT): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(style)
        .withLocale(LocalLocale.current.platformLocale)
    return Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(formatter)
}