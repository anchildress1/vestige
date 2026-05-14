package dev.anchildress1.vestige

import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.newModuleTempRoot
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import java.io.File

/**
 * Robolectric `@Config(application = ...)` stand-in. Production `VestigeApplication.onCreate`
 * builds a real `AppContainer` against the host filesystem; under JVM tests we need an in-memory
 * BoxStore and a temp markdown root so suites stay hermetic and parallel-safe. Robolectric
 * instantiates the application class itself, so a subclass override is the only seam — there is
 * no constructor we can inject through.
 */
class TestVestigeApplication : VestigeApplication() {

    private val tempRoot: File by lazy { newModuleTempRoot("vestige-application-") }

    override fun createAppContainer(): AppContainer = AppContainer(
        applicationContext = this,
        boxStoreFactory = { _ -> openInMemoryBoxStore(newInMemoryObjectBoxDirectory("vestige-app-container-")) },
        markdownStoreFactory = { _ -> MarkdownEntryStore(File(tempRoot, "markdown").apply { mkdirs() }) },
    )

    /**
     * Test hook — Robolectric reuses the JVM, so callers must release both the in-memory BoxStore
     * (native registry, leaks into the next suite otherwise) and the lazy markdown temp root.
     */
    fun releaseTempStorage() {
        // appContainer is `lateinit var` with `private set` — `::appContainer.isInitialized` isn't
        // accessible from a subclass, so swallow the UninitializedPropertyAccessException instead.
        runCatching { appContainer.boxStore.close() }
        if (tempRoot.exists()) {
            check(tempRoot.deleteRecursively()) { "Failed to delete test temp root: $tempRoot" }
        }
    }
}
