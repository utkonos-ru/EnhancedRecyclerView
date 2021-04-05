package ru.utkonos.enhanced_recycler_vew

import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.R
import androidx.recyclerview.widget.RecyclerView
import java.util.*

internal val View.isInParentBounds: Boolean
    get() {
        val parentBounds = Rect().also { (parent as? View)?.getHitRect(it) ?: return false }
        return if (getLocalVisibleRect(parentBounds))
            parentBounds.width() >= width && parentBounds.height() >= height
        else
            false
    }

internal fun ViewDataBinding.setVariable(name: String, value: Any?) {
    val setterName = "set${name.capitalize(Locale.getDefault())}"
    this::class.java.declaredMethods.firstOrNull {
        val parameterTypes = it.parameterTypes
        it.name == setterName
                && parameterTypes.size == 1
                && if (value != null) parameterTypes[0].isAssignableFrom(value::class.java) else true
    }?.invoke(this, value)
}

fun EnhancedRecyclerView.SuspendGetNextPage.doOnInvoke(block: (List<Any?>?) -> Unit) =
    object : EnhancedRecyclerView.SuspendGetNextPage {
        override suspend fun invoke() = this@doOnInvoke.invoke().also(block)
    }

fun EnhancedRecyclerView.GetNextPageSingle.doOnSuccess(block: (List<Any?>) -> Unit) =
    object : EnhancedRecyclerView.GetNextPageSingle {
        override fun invoke() = this@doOnSuccess.invoke().doOnSuccess(block)
    }