package com.thelightphone.toolmanager.datatree

import com.thelightphone.toolmanager.DataView

// A BranchDataProvider whose children are a fixed, precomputed list — used for statically
// declared branches (e.g. hand-built in Main.kt) as opposed to dynamically discovered ones.
class StaticBranchProvider(private val children: List<DataView<*>>) : BranchDataTree {
    override suspend fun getChildren(): List<DataView<*>> = children
}
