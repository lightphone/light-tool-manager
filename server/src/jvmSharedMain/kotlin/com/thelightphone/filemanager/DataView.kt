package com.thelightphone.filemanager

// Pairs a page's presentation (spec: label/path/kind) with the provider backing it. Whoever
// constructs a DataView is responsible for pairing a RootViewSpec with a BranchDataProvider and
// a FileBrowserSpec/DropboxSpec/ConfiguratorSpec with a LeafDataProvider — the client dispatches
// screens off `spec`'s sealed type, while RootDataProvider dispatches behavior off `provider`'s.
data class DataView(
    val spec: DataViewSpec,
    val provider: FileTree
)
