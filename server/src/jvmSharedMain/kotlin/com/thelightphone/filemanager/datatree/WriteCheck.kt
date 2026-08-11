package com.thelightphone.filemanager.datatree

import com.thelightphone.filemanager.Entry

sealed interface WriteCheck {
    data object Safe : WriteCheck
    data class FileExists(val existing: Entry) : WriteCheck
    data object DirectoryExists : WriteCheck
    data object InvalidPath : WriteCheck
    data object ReadOnly : WriteCheck
}
