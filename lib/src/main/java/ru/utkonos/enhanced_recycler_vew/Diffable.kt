package ru.utkonos.enhanced_recycler_vew

interface Diffable : Identifiable {

    fun areContentsTheSame(other: Diffable): Boolean
}

open class DiffableImpl internal constructor(override val id: Any): Diffable {
    override fun areContentsTheSame(other: Diffable) = this == other
}

fun Diffable(id: Any) = DiffableImpl(id)

interface DiffableWithSameClass<T : Diffable> : Diffable {

    fun minimizeDiff(other: T): T
}

class DiffableWithSameClassImpl<T: Diffable> internal constructor(override val id: Any): DiffableImpl(id), DiffableWithSameClass<T> {

    @Suppress("UNCHECKED_CAST")
    override fun areContentsTheSame(other: Diffable): Boolean {
        if (this.javaClass != other.javaClass) return false
        return super.areContentsTheSame(minimizeDiff(other as T))
    }

    override fun minimizeDiff(other: T) = other
}

fun <T: Diffable> DiffableWithSameClass(id: Any) = DiffableWithSameClassImpl<T>(id)