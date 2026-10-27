package com.gasserelshafei.examorganizer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime

/** The online-lesson rules: the same cases as the desktop app's meetings tests. */
class LessonsTest {
    @Test fun parsesTimes() {
        mapOf("9" to "09:00", "9:30" to "09:30", "09.30" to "09:30", "9:30 am" to "09:30", "2pm" to "14:00",
            "12am" to "00:00", "12 pm" to "12:00", "14:00" to "14:00", " 7:05PM " to "19:05").forEach { (text, want) ->
            assertEquals(text, want, Lessons.parseTime(text))
        }
        listOf("", "25:00", "9:75", "13pm", "noon", "9:3").forEach {
            try { Lessons.parseTime(it); fail("should reject '$it'") } catch (_: UserError) { }
        }
    }

    @Test fun checksLinks() {
        assertEquals("https://us02web.zoom.us/j/123?pwd=x", Lessons.checkLink(" https://us02web.zoom.us/j/123?pwd=x "))
        assertEquals("https://us02web.zoom.us/j/123", Lessons.checkLink("us02web.zoom.us/j/123"))
        listOf("zoom meeting 123", "https://zoom.us/j/1 2").forEach {
            try { Lessons.checkLink(it); fail(it) } catch (_: UserError) { }
        }
    }

    @Test fun rendersMessages() {
        val sunday9 = LocalDateTime.of(2026, 10, 18, 9, 0)
        val setup = LessonSetup("c", link = "https://zoom.us/j/111", groupId = "1@g.us", times = listOf(LessonTime(weekday = 7, start = "09:00")))
        val text = Lessons.render(setup, "10B", sunday9)
        assertTrue(text, "10B" in text && "09:00" in text && "https://zoom.us/j/111" in text && "Passcode" !in text)
        assertEquals("Lesson Sunday 09:00! https://zoom.us/j/111\nCode: abc123",
            Lessons.render(setup.copy(passcode = "abc123", message = "Lesson {day} {time}! {link}\nCode: {passcode}"), "10B", sunday9))
        assertTrue(Lessons.render(setup.copy(message = "See you soon"), "10B").endsWith("\nhttps://zoom.us/j/111"))
    }

    private fun book(): Pair<LessonBook, File> {
        val dir = Files.createTempDirectory("lessons").toFile()
        val scoreBook = File(dir, "scorebook.json")
        scoreBook.writeText(JSONObject().put("classes", JSONArray(listOf(
            JSONObject().put("id", "b").put("name", "10B"), JSONObject().put("id", "y").put("name", "Year 11")))).toString())
        return LessonBook(File(dir, "lessons.json"), scoreBook) to dir
    }

    @Test fun sendsOnTimeOnceOnly() {
        val (book, dir) = book()
        val now = LocalDateTime.of(2026, 10, 18, 8, 0)   // a Sunday
        book.update("b") { it.copy(link = "https://zoom.us/j/111", groupId = "1@g.us", groupName = "10B Parents") }
        book.addTime("b", "9:00", 7, null)
        book.addTime("b", "11:15", 2, null)
        try { book.addTime("b", "09:00", 7, null); fail("duplicate") } catch (_: UserError) { }

        assertTrue(book.due(now).isEmpty())
        val due = book.due(now.withMinute(50))
        assertEquals(1, due.size)
        assertEquals(now.withHour(9), due[0].lessonAt)
        assertEquals("10B", due[0].className)
        assertEquals(1, book.due(now.withHour(9).withMinute(9)).size)    // late, still worth it
        assertTrue(book.due(now.withHour(9).withMinute(10)).isEmpty())   // too late

        // The alarm: at 8:50, and right away when due
        assertEquals(now.withMinute(50), book.nextWake(now))
        assertEquals(now.withMinute(50).plusSeconds(5), book.nextWake(now.withMinute(50)))

        book.record("b", due[0].lesson.id, due[0].lessonAt, true)
        assertTrue(book.due(now.withMinute(55)).isEmpty())
        assertEquals(1, book.history("b").size)
        // After sending, the next alarm is Tuesday's lesson
        assertEquals(LocalDateTime.of(2026, 10, 20, 11, 5), book.nextWake(now.withMinute(55)))

        // Paused classes send nothing; a reloaded book remembers everything
        book.update("b") { it.copy(paused = true) }
        assertTrue(book.due(now.withMinute(50).plusDays(7)).isEmpty())
        assertNull(book.nextWake(now))
        book.update("b") { it.copy(paused = false) }
        val again = LessonBook(File(dir, "lessons.json"), File(dir, "scorebook.json"))
        assertEquals(1, again.due(now.withMinute(50).plusDays(7)).size)
        assertEquals("10B Parents", again.setup("b").groupName)
        assertEquals(2, again.setup("b").times.size)
    }

    @Test fun acrossMidnightAndOneOffDates() {
        val (book, _) = book()
        val now = LocalDateTime.of(2026, 10, 18, 23, 45)   // Sunday night
        book.update("y") { it.copy(link = "https://zoom.us/j/222", groupId = "2@g.us", groupName = "Year 11", minutesBefore = 30) }
        book.addTime("y", "00:10", 1, null)   // Monday 00:10, so the link goes out on Sunday at 23:40
        assertEquals(listOf("Year 11"), book.due(now).map { it.className })

        val future = LocalDate.now().plusDays(3)
        book.addTime("y", "15:30", null, future)
        val oneOff = book.setup("y").times.first { it.date != null }
        assertTrue(oneOff.describe.startsWith("Once, "))
        assertEquals(listOf(future.toString()), book.due(future.atTime(15, 5)).map { it.lesson.date })
        try { book.addTime("y", "10:00", null, LocalDate.now().minusDays(1)); fail("past") } catch (_: UserError) { }
    }

    @Test fun lateLinkFromBeforeMidnightIsCaughtUp() {
        val (book, _) = book()
        val now = LocalDateTime.of(2026, 10, 19, 0, 2)   // Monday, 7 minutes after Sunday's 23:55 lesson
        book.update("b") { it.copy(link = "https://zoom.us/j/111", groupId = "1@g.us", groupName = "10B Parents") }
        book.addTime("b", "23:55", 7, null)
        assertEquals(1, book.due(now).size)
        // Still due after midnight (a retry, or the phone restarted): wake straight away, not next week
        assertEquals(LocalDateTime.of(2026, 10, 18, 23, 55), Lessons.nextMessage(book.setup("b"), now)?.second)
        assertEquals(now.plusSeconds(5), book.nextWake(now))
        // Once it's too late, the next one is next Sunday's
        assertEquals(LocalDateTime.of(2026, 10, 25, 23, 45), book.nextWake(now.withMinute(5)))
    }

    @Test fun failedSendsAreRetried() {
        val (book, _) = book()
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        val lesson = now.plusMinutes(5)
        book.update("b") { it.copy(link = "https://zoom.us/j/111", groupId = "1@g.us", groupName = "10B Parents") }
        book.addTime("b", "%02d:%02d".format(lesson.hour, lesson.minute), lesson.dayOfWeek.value, null)
        val due = book.due(now)
        assertEquals(1, due.size)
        book.record("b", due[0].lesson.id, due[0].lessonAt, false, "WhatsApp isn't linked on this phone.")
        assertTrue("waits before retrying", book.due(now).isEmpty())
        val wake = book.nextWake(now)!!
        assertTrue("retry in about 2 minutes: $wake", wake.isAfter(now.plusMinutes(1)) && wake.isBefore(now.plusMinutes(3)))
        assertEquals(1, book.due(now.plusMinutes(3)).size)
    }

    @Test fun deletedClassesAreSkipped() {
        val (book, _) = book()
        book.update("gone") { it.copy(link = "https://zoom.us/j/9", groupId = "9@g.us", times = listOf(LessonTime(weekday = 7, start = "09:00"))) }
        assertTrue(book.due(LocalDateTime.of(2026, 10, 18, 8, 55)).isEmpty())
        assertNull(book.nextWake(LocalDateTime.of(2026, 10, 18, 8, 0)))
    }
}
