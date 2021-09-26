package ru.utkonos.enhanced_recycler_vew

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

class FittableLayoutManager(
    context: Context,
    @RecyclerView.Orientation orientation: Int,
    reverseLayout: Boolean
) : LinearLayoutManager(context, orientation, reverseLayout) {

    override fun generateDefaultLayoutParams() =
        spanLayoutSize(super.generateDefaultLayoutParams())

    override fun generateLayoutParams(
        c: Context?,
        attrs: AttributeSet?
    ) = spanLayoutSize(super.generateLayoutParams(c, attrs))

    override fun generateLayoutParams(lp: ViewGroup.LayoutParams) =
        spanLayoutSize(super.generateLayoutParams(lp))

    override fun canScrollVertically() = false
    override fun canScrollHorizontally() = false

    private val horizontalSpace: Int
        get() = width - paddingRight - paddingLeft
    private val verticalSpace: Int
        get() = height - paddingBottom - paddingTop

    private fun spanLayoutSize(layoutParams: RecyclerView.LayoutParams): RecyclerView.LayoutParams {
        if (orientation == HORIZONTAL) {
            layoutParams.width = (horizontalSpace.toDouble() / itemCount).roundToInt() -
                    layoutParams.leftMargin -
                    layoutParams.rightMargin
        } else if (orientation == VERTICAL) {
            layoutParams.height = (verticalSpace.toDouble() / itemCount).roundToInt() -
                    layoutParams.topMargin -
                    layoutParams.bottomMargin
        }

        return layoutParams
    }
}
