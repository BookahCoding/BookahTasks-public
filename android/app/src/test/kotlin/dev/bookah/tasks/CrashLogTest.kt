package dev.bookah.tasks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
    @Test fun keepsClassNamesAndFramesButNeverTheMessage() {
        val secret = "Unable to resolve host \"private.example.net\""
        val text = CrashLog.describe(RuntimeException("outer", java.net.UnknownHostException(secret)), "main")
        assertFalse(text.contains("private.example.net"))
        assertFalse(text.contains("outer"))
        assertTrue(text.contains("java.lang.RuntimeException"))
        assertTrue(text.contains("caused by java.net.UnknownHostException"))
        assertTrue(text.contains("at dev.bookah.tasks.CrashLogTest"))
    }
}
