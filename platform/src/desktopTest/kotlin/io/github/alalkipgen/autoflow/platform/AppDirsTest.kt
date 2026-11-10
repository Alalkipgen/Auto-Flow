package io.github.alalkipgen.autoflow.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class AppDirsTest {
    @Test
    fun desktopRootIsDotAutoflowInTheHomeFolderWithPortableSeparators() {
        val dirs = AppDirs.desktop(userHome = "C:\\Users\\Aung", override = null)
        assertEquals("C:/Users/Aung/.autoflow", dirs.root)
        assertEquals("C:/Users/Aung/.autoflow/projects.json", dirs.projectsIndex)
        assertEquals("C:/Users/Aung/.autoflow/settings.json", dirs.settings)
        assertEquals("C:/Users/Aung/.autoflow/secrets.bin", dirs.secrets)
        assertEquals("C:/Users/Aung/.autoflow/usage.json", dirs.usage)
    }

    @Test
    fun overridePropertyWinsUnlessBlank() {
        assertEquals("D:/af-home", AppDirs.desktop(userHome = "/home/a", override = "D:\\af-home\\").root)
        assertEquals("/home/a/.autoflow", AppDirs.desktop(userHome = "/home/a", override = " ").root)
    }
}
