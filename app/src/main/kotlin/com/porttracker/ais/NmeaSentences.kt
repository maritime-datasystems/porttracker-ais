package com.porttracker.ais

/** JNI delivers newline-separated batches; consumers require individual sentences. */
internal fun nmeaSentences(batch: String): Sequence<String> =
    batch.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
