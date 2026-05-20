package dev.anchildress1.vestige.storage

import io.objectbox.BoxStore

fun <T> BoxStore.callClosingThreadResources(block: () -> T): T = try {
    block()
} finally {
    closeThreadResources()
}

fun BoxStore.closeAfterCleaningThreadResources() {
    if (isClosed) return
    closeThreadResources()
    runCatching { cleanStaleReadTransactions() }
    close()
}
