package com.thelightphone.toolmanager

import java.util.Locale
import kotlin.time.Duration

fun Duration.formatClock(locale: Locale = Locale.US): String =
    toComponents { days, hours, minutes, seconds, _ ->
        when {
            days > 0 -> String.format(locale, "%d:%02d:%02d:%02d", days, hours, minutes, seconds)
            hours > 0 -> String.format(locale, "%02d:%02d:%02d", hours, minutes, seconds)
            else -> String.format(locale, "%02d:%02d", minutes, seconds)
        }
    }
