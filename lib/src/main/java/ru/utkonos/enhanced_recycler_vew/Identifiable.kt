package ru.utkonos.enhanced_recycler_vew

import kotlin.reflect.KClass

interface Identifiable {
    val id: Any
}

interface IdentifiableByClass : Identifiable {
    override val id: String
}

open class IdentifiableByClassImpl internal constructor(private val clazz: KClass<*>): IdentifiableByClass {
    override val id: String get() = clazz.qualifiedName!!
}

fun Any.IdentifiableByClass() = IdentifiableByClassImpl(this::class)

