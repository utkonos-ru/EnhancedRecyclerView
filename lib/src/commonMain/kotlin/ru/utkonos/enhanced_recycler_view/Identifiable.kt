package ru.utkonos.enhanced_recycler_view

interface Identifiable {
    val id: Any
}

interface IdentifiableByClass : Identifiable {
    override val id: String get() = this::class.qualifiedName!!
}