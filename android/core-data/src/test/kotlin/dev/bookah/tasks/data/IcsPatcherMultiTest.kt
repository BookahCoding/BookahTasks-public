package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CR = "\r\n"
private val SAMPLE = listOf(
    "BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VTODO", "UID:c1", "SUMMARY;LANGUAGE=el:Παλιός τίτλος",
    "RELATED-TO:parent-1", "RELATED-TO;RELTYPE=SIBLING:sib-1",
    "CATEGORIES:home,someday", "CATEGORIES:errands",
    "DUE;TZID=Europe/Athens:20261120T190000",
    "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT30M", "END:VALARM",
    "END:VTODO", "END:VCALENDAR",
).joinToString(CR) + CR

class IcsPatcherMultiTest {
    @Test fun keepParamsReusesTheLinesOwnParameters() {
        val out = IcsPatcher.setProperty(SAMPLE, "SUMMARY", IcsPatcher.escapeText("Νέος τίτλος"), keepParams = true)
        assertTrue(out.contains("SUMMARY;LANGUAGE=el:Νέος τίτλος"))
    }

    @Test fun withoutKeepParamsTheOldParametersAreReplaced() {
        val out = IcsPatcher.setProperty(SAMPLE, "DUE", "20261120T170000Z")
        assertTrue(out.contains("DUE:20261120T170000Z"))
        assertTrue(!out.contains("TZID"))
    }

    @Test fun everyRepeatedLineCanBeRead() {
        assertEquals(listOf("home,someday", "errands"), IcsPatcher.readAllProperties(SAMPLE, "CATEGORIES"))
        assertEquals(listOf("parent-1", "sib-1"), IcsPatcher.readAllProperties(SAMPLE, "RELATED-TO"))
        assertEquals(emptyList<String>(), IcsPatcher.readAllProperties(SAMPLE, "ATTACH"))
    }

    @Test fun editingOneSingleValuedPropertyLeavesRepeatedLinesAndAlarmsAlone() {
        val out = IcsPatcher.setProperty(SAMPLE, "SUMMARY", "x", keepParams = true)
        assertEquals(listOf("parent-1", "sib-1"), IcsPatcher.readAllProperties(out, "RELATED-TO"))
        assertTrue(out.contains("BEGIN:VALARM" + CR + "ACTION:DISPLAY" + CR + "TRIGGER:-PT30M" + CR + "END:VALARM"))
    }
}
