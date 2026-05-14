package dev.anchildress1.vestige

import dev.anchildress1.vestige.storage.MarkdownEntryStore
import dev.anchildress1.vestige.testing.newInMemoryObjectBoxDirectory
import dev.anchildress1.vestige.testing.newModuleTempRoot
import dev.anchildress1.vestige.testing.openInMemoryBoxStore
import java.io.File

class TestVestigeApplication : VestigeApplication() {

    private val tempRoot: File by lazy { newModuleTempRoot("vestige-application-") }

    override fun createAppContainer(): AppContainer = AppContainer(
        applicationContext = this,
        boxStoreFactory = { openInMemoryBoxStore(newInMemoryObjectBoxDirectory("vestige-app-container-")) },
        markdownStoreFactory = { MarkdownEntryStore(File(tempRoot, "markdown").apply { mkdirs() }) },
    )
}
