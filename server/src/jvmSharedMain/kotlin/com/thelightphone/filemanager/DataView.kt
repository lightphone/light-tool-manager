package com.thelightphone.filemanager

import com.thelightphone.filemanager.datatree.BranchDataTree
import com.thelightphone.filemanager.datatree.DataTree
import com.thelightphone.filemanager.datatree.LeafDataTree

// Pairs a page's presentation (spec) with the provider backing it.
sealed interface DataView<T : DataTree> {
    val spec: DataViewSpec
    val provider: T
    val isHidden: Boolean
}

class BranchView(
    override val spec: BranchViewSpec,
    override val provider: BranchDataTree,
    forceHide: Boolean = false
) : DataView<BranchDataTree> {
    override val isHidden = forceHide
}

class LeafView(
    override val spec: LeafViewSpec,
    override val provider: LeafDataTree,
    forceHide: Boolean = false
) : DataView<LeafDataTree> {
    override val isHidden = forceHide || spec is CustomSpec
}
