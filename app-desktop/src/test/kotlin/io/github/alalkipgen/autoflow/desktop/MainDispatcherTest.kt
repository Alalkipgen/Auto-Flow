package io.github.alalkipgen.autoflow.desktop

import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * D-61: the desktop runtime classpath must provide Dispatchers.Main. `:ui` models are created with `MainScope()`
 * (UiModule); without kotlinx-coroutines-swing the packaged app crashes at start with
 * "Module with the Main dispatcher is missing".
 */
class MainDispatcherTest {
    @Test fun mainDispatcherIsTheSwingEventThread() = runBlocking {
        val onEdt = withTimeout(10_000) { withContext(Dispatchers.Main) { SwingUtilities.isEventDispatchThread() } }
        assertTrue(onEdt, "Dispatchers.Main must run on the Swing EDT")
    }

    @Test fun mainScopeLaunchWorksLikeTheUiModels() = runBlocking {
        val scope = MainScope()
        val done = CompletableDeferred<Boolean>()
        scope.launch { done.complete(true) } // LibraryModel.<init> does exactly this
        assertTrue(withTimeout(10_000) { done.await() })
        scope.cancel()
    }
}
