package io.github.alalkipgen.autoflow.desktop

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.settings.LoadedModelConfig
import io.github.alalkipgen.autoflow.ui.settings.ProviderStatusModel
import java.nio.file.Files
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.koin.dsl.koinApplication

/** The real desktop graph (JvmFs, DPAPI or Unsupported, bundled models.json) in a temporary `autoflow.home`. */
class DesktopModuleTest {
    private val home = Files.createTempDirectory("autoflow-home")
    private val app = run {
        System.setProperty(AppDirs.HOME_OVERRIDE_PROPERTY, home.toString())
        koinApplication { modules(desktopModule(), uiModule, aiModule) }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        app.close()
        System.clearProperty(AppDirs.HOME_OVERRIDE_PROPERTY)
        home.deleteRecursively()
    }

    @Test
    fun graphResolvesWithTheBundledModelConfigAndStartsInFakeMode() = runBlocking {
        val models = app.koin.get<LoadedModelConfig>()
        assertEquals(LoadedModelConfig.BUNDLED, models.source)
        assertNull(models.problem)
        assertTrue(models.config.text.candidates.isNotEmpty())
        assertTrue(app.koin.get<ProviderStatusModel>().current().startsWith("AI ● Fake (offline)"))
        assertIs<AiResult.Ok<*>>(app.koin.get<TextProvider>().json("hello", JsonSchema(JsonObject(emptyMap()))))
        assertEquals(home.toString().replace('\\', '/'), app.koin.get<AppDirs>().root)
    }
}
