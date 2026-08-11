package com.thelightphone.filemanager.datatree

import java.io.OutputStream

// Lets a DataProvider stage a write and only make it visible (commit) once the
// caller-supplied block finishes successfully, so an aborted upload can roll
// back instead of leaving partial data at the destination path.
class WriteTarget(
    val outputStream: OutputStream,
    private val onValidate: () -> Boolean = { true },
    private val onCommit: () -> Unit = {},
    private val onRollback: () -> Unit = {}
) {
    fun tryCommit() = if (onValidate()) {
        onCommit()
        true
    } else {
        onRollback()
        false
    }

    fun rollback() = onRollback()
}
