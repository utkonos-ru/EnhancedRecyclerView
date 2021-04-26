package ru.utkonos.enhanced_recycler_view

import androidx.databinding.BindingAdapter
import ru.utkonos.enhanced_recycler_vew.EnhancedRecyclerView

object EnhancedRecyclerViewBinder {

    @JvmStatic
    @BindingAdapter("activity")
    fun setActivity(view: EnhancedRecyclerView, value: MainActivity) {
        EnhancedRecyclerView.setOnItemBound(
            view,
            object : EnhancedRecyclerView.OnViewDataBindingHolderUpdated {
                override fun invoke(viewHolder: EnhancedRecyclerView.ViewDataBindingHolder) {
                    viewHolder.itemBinding.setVariable(BR.activity, value)
                }
            }
        )
    }
}