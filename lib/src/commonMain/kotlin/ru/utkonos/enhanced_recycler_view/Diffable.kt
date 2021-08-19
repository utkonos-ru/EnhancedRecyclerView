package ru.utkonos.enhanced_recycler_view

interface Diffable : Identifiable {

    fun areContentsTheSame(other: Diffable) = this == other
}


interface DiffableWithSameClass<T : Diffable> : Diffable {

    @Suppress("UNCHECKED_CAST")
    override fun areContentsTheSame(other: Diffable): Boolean {
        if (this::class != other::class) return false
        return super.areContentsTheSame(minimizeDiff(other as T))
    }

    fun minimizeDiff(other: T) = other
}