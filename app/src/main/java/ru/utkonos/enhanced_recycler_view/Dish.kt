package ru.utkonos.enhanced_recycler_view

import ru.utkonos.enhanced_recycler_vew.DiffableWithSameClass
import java.util.*
import kotlin.random.Random

data class Dish(
    override val id: Long,
    val name: String,
    val canChoose: Boolean,
    val code: UUID = UUID.randomUUID(),
    val categoryId: Long = Random.nextLong(),
) : DiffableWithSameClass<Dish> {

    override fun minimizeDiff(other: Dish) = other.copy(code = code, categoryId = categoryId)
}