package dev.anchildress1.vestige.storage

import io.objectbox.BoxStore

fun <T> BoxStore.callInReadTxClosingThreadResources(block: () -> T): T = try {
    callInReadTx<T> { block() }
} finally {
    closeThreadResources()
}

fun BoxStore.closeAfterCleaningThreadResources() {
    closeThreadResources()
    cleanStaleReadTransactions()
    close()
}
