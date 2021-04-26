package ru.utkonos.enhanced_recycler_vew

interface Identifiable {
    val id: Any
}

interface IdentifiableByClass : Identifiable {
    override val id: String get() = this::class.qualifiedName!!
}