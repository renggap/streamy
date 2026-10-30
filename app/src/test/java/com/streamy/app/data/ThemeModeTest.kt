package com.streamy.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test fun parses_each_stored_name() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.parse("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.parse("DARK"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("SYSTEM"))
    }

    @Test fun case_and_whitespace_insensitive() = assertEquals(ThemeMode.DARK, ThemeMode.parse(" dark "))

    // Existing installs have no stored value and must keep the light look they have today.
    @Test fun missing_blank_or_garbage_defaults_to_light() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.parse(null))
        assertEquals(ThemeMode.LIGHT, ThemeMode.parse(""))
        assertEquals(ThemeMode.LIGHT, ThemeMode.parse("amoled"))
    }

    @Test fun light_and_dark_ignore_the_system() {
        for (systemDark in listOf(true, false)) {
            assertEquals(false, ThemeMode.LIGHT.isDark(systemDark))
            assertEquals(true, ThemeMode.DARK.isDark(systemDark))
        }
    }

    @Test fun system_follows_the_device() {
        assertEquals(true, ThemeMode.SYSTEM.isDark(true))
        assertEquals(false, ThemeMode.SYSTEM.isDark(false))
    }
}
