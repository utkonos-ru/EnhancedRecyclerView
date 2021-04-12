package ru.utkonos.enhanced_recycler_vew

interface Identifiable {
    val id: Any

    fun contentEquals(other: Any?) = this == other
}

interface IdentifiableByClass : Identifiable {
    override val id: String get() = this::class.qualifiedName!!
}