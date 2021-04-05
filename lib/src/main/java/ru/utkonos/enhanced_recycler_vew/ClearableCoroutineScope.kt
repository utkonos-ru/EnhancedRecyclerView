package ru.utkonos.enhanced_recycler_vew

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

internal interface ClearableCoroutineScope : CoroutineScope {
    fun clear()
}

internal fun ClearableCoroutineScope(context: CoroutineContext) = object : ClearableCoroutineScope {

    override var coroutineContext: CoroutineContext = context + Job()

    override fun clear() {
        coroutineContext[Job]?.cancel()
            ?.also { coroutineContext = coroutineContext.minusKey(Job) + Job() }
    }
}
