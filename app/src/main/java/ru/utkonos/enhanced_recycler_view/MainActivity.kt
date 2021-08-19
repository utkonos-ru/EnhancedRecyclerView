package ru.utkonos.enhanced_recycler_view

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.ObservableField
import ru.utkonos.enhanced_recycler_view.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    val dishes = ObservableField(
        listOf(
            Dish(1, "Pizza", true),
            Dish(2, "Sushi", false),
            Dish(3, "Pasta", true),
            Dish(4, "Borscht", true),
            Dish(5, "French fries", false)
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ActivityMainBinding.inflate(layoutInflater).apply { activity = this@MainActivity }.root
        )
    }

    fun refresh() {
        dishes.set(dishes.get()?.map { it.copy() }?.shuffled())
    }

    fun onDishChoose(dish: Dish) {
        Toast.makeText(
            this@MainActivity,
            if (dish.canChoose) R.string.good_choice else R.string.no_dish,
            Toast.LENGTH_SHORT
        ).show()
    }
}