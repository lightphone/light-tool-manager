package com.thelightphone.toolmanager

import com.thelightphone.toolmanager.datatree.BranchDataTree
import com.thelightphone.toolmanager.datatree.DataTree
import com.thelightphone.toolmanager.datatree.LeafDataTree

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
