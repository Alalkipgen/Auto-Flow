package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopToolLocatorTest {
    private val fs = InMemoryFileSystem()

    private fun file(path: String) {
        fs.createDirectories(path.substringBeforeLast('/'))
        fs.writeDurably(path, byteArrayOf(1))
    }

    @Test
    fun findsFfmpegOnTheWindowsPath() {
        file("/C:/ffmpeg/bin/ffmpeg.exe")
        val env = mapOf("PATH" to "C:\\Windows;\"/C:/ffmpeg/bin\";")
        val locator = DesktopToolLocator(fs, env::get, "Windows 11")
        assertEquals("/C:/ffmpeg/bin/ffmpeg.exe", locator.findFfmpeg())
        assertNull(DesktopToolLocator(fs, mapOf("PATH" to "C:\\Windows")::get, "Windows 11").findFfmpeg())
        assertNull(DesktopToolLocator(fs, { null }, "Windows 11").findFfmpeg())
    }

    @Test
    fun findsVlcInProgramFilesOnlyWhenLibvlcIsThere() {
        val env = mapOf("ProgramFiles" to "D:\\Apps")
        val locator = DesktopToolLocator(fs, env::get, "Windows 11")
        assertNull(locator.findVlc())
        fs.createDirectories("D:/Apps/VideoLAN/VLC")
        assertNull(locator.findVlc(), "empty folder is not an install")
        file("D:/Apps/VideoLAN/VLC/libvlc.dll")
        assertEquals("D:/Apps/VideoLAN/VLC", locator.findVlc())
        assertTrue(locator.isVlcDir("D:\\Apps\\VideoLAN\\VLC"))
        assertFalse(locator.isVlcDir("D:/Apps"))
    }
}
