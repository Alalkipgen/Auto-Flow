package io.github.alalkipgen.autoflow.platform

import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.IOException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import java.awt.datatransfer.Clipboard as AwtClipboardBuffer

class AwtClipboardTest {
    @Test
    fun copiesPlainText() {
        val buffer = AwtClipboardBuffer("test")
        assertEquals(PlatformResult.Ok(Unit), AwtClipboard(target = { buffer }).copyText("မင်္ဂလာပါ prompt"))
        assertEquals("မင်္ဂလာပါ prompt", buffer.getData(DataFlavor.stringFlavor))
    }

    @Test
    fun retriesWhileAnotherAppHoldsTheClipboard() {
        val busy = BusyClipboard(failures = 2)
        val sleeps = mutableListOf<Long>()
        assertEquals(PlatformResult.Ok(Unit), AwtClipboard(target = { busy }, sleep = { sleeps += it }).copyText("x"))
        assertEquals(listOf(30L, 60L), sleeps)
        assertIs<PlatformResult.Failed>(AwtClipboard(target = { BusyClipboard(failures = 9) }, sleep = {}).copyText("x"))
    }

    @Test
    fun headlessSessionIsUnsupported() {
        assertIs<PlatformResult.Unsupported>(AwtClipboard().copyText("x"))
        assertIs<PlatformResult.Unsupported>(AwtClipboard(target = { null }).copyText("x"))
    }

    private class BusyClipboard(private var failures: Int) : AwtClipboardBuffer("busy") {
        override fun setContents(contents: Transferable?, owner: ClipboardOwner?) {
            if (failures-- > 0) throw IllegalStateException("cannot open system clipboard")
            super.setContents(contents, owner)
        }
    }
}

class DesktopUrlOpenerTest {
    private val opened = mutableListOf<URI>()
    private val opener = DesktopUrlOpener(browser = { opened += it })

    @Test
    fun opensHttpAndHttpsLinks() {
        assertEquals(PlatformResult.Ok(Unit), opener.open("https://labs.google/fx/tools/flow"))
        assertEquals(PlatformResult.Ok(Unit), opener.open(" http://localhost:8080/x?q=1 "))
        assertEquals(listOf(URI("https://labs.google/fx/tools/flow"), URI("http://localhost:8080/x?q=1")), opened)
    }

    @Test
    fun refusesEverythingElseWithoutCallingTheBrowser() {
        listOf("file:///C:/Windows/system32/calc.exe", "javascript:alert(1)", "ftp://x.org", "aistudio.google.com", "",
            "https://user@evil.example", "https://")
            .forEach { assertIs<PlatformResult.Failed>(opener.open(it), it) }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun reportsMissingOrFailingBrowser() {
        assertIs<PlatformResult.Unsupported>(DesktopUrlOpener(browser = null).open("https://example.org"))
        assertIs<PlatformResult.Unsupported>(DesktopUrlOpener().open("https://example.org")) // headless test JVM
        val failing = DesktopUrlOpener(browser = { throw IOException("no handler") })
        assertEquals(PlatformResult.Failed("Could not open the browser: no handler"), failing.open("https://example.org"))
    }
}

class FilePickerTest {
    @Test
    fun headlessSessionIsUnsupported() = runTest {
        val picker = SwingFilePicker()
        assertIs<PlatformResult.Unsupported>(picker.pickFolder("Open project"))
        assertIs<PlatformResult.Unsupported>(picker.pickFiles("Import video", setOf("mp4")))
    }

    @Test
    fun extensionMatchingIsCaseInsensitiveAndIgnoresFolders() {
        assertTrue(hasExtension("C:/in/Shot 3.MP4", setOf("mp4", "mov")))
        assertTrue(hasExtension("ref.jpeg", setOf(".jpeg")))
        assertTrue(hasExtension("anything", emptySet()))
        assertFalse(hasExtension("C:/in.mp4/readme", setOf("mp4")))
        assertFalse(hasExtension("noext", setOf("mp4")))
    }

    @Test
    fun pickedFilesOfOtherTypesAreDroppedAndOnlyThoseIsAnError() {
        assertEquals(PlatformResult.Ok(listOf("a.png")), filterPicked(listOf("a.png", "b.txt"), setOf("png", "jpg")))
        assertEquals(PlatformResult.Ok(emptyList()), filterPicked(emptyList(), setOf("png")))
        assertEquals(PlatformResult.Failed("Choose a .jpg / .png file"), filterPicked(listOf("b.txt"), setOf("png", "jpg")))
    }

    @Test
    fun webUrlCheck() {
        assertTrue(isWebUrl("https://aistudio.google.com/prompts/new_chat"))
        assertFalse(isWebUrl("https://"))
        assertFalse(isWebUrl("mailto:a@b.c"))
    }
}
