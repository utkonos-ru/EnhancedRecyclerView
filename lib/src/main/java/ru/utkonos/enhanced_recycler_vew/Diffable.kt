@file:Suppress("UNCHECKED_CAST")

package ru.utkonos.enhanced_recycler_vew

interface Diffable : Identifiable {

    fun areContentsTheSame(other: Diffable) = this == other
}


interface DiffableWithSameClass<T : Diffable> : Diffable {

    override fun areContentsTheSame(other: Diffable): Boolean {
        if (this.javaClass != other.javaClass) return false
        return super.areContentsTheSame(minimizeDiff(other as T))
    }

    fun minimizeDiff(other: T) = other
}