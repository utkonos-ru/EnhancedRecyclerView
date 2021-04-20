package ru.utkonos.enhanced_recycler_vew

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.databinding.*
import androidx.recyclerview.widget.*
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.parcel.Parcelize
import kotlinx.android.parcel.RawValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.*
import kotlin.math.min

open class EnhancedRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    var maxNumberOfItemsToSave = 30

    private var pendingState: SavedState? = null

    @Volatile
    private var isBindingAsNested = false

    private val canRestoreState get() = !isBindingAsNested && adapter != null

    private var itemAnimatorBackup: RecyclerView.ItemAnimator? = null

    private var list: List<Any?>? = null
        set(value) {
            if (field === value) return
            field = value
            updateAdapter()
        }

    private var getItemLayout: GetItemLayout? = null
        set(value) {
            if (field === value) return
            field = value
            updateAdapter()
        }

    private var onDataChangedCallback: OnDataChangedCallback? = null

    var getNextPageLoadingPosition
        get() = paginationScrollListener?.getNextPageLoadingPosition
        set(value) {
            paginationScrollListener?.getNextPageLoadingPosition = value
        }
    var getNextPage: GetNextPage? = null
        set(value) {
            if (field === value) return
            field = value
            updatePaginationScrollListener()
        }
    private var paginationScrollListener: PaginationScrollListener? = null
    var lastPageSize: Int? = null
        private set

    private var onItemCreated: OnViewDataBindingHolderUpdated? = null
    private var onItemBound: OnViewDataBindingHolderUpdated? = null
    private var onItemRecycled: OnViewDataBindingHolderUpdated? = null
    var onItemIsFullyVisible: OnViewHolderUpdated? = null
        set(value) {
            if (field === value) return
            field = value
            updateItemIsFullyVisibleListener()
        }
    private var itemIsFullyVisibleListener: ItemIsFullyVisibleListener? = null

    private val sharedRecycledViewPool = RecycledViewPool()

    init {
        setRecycledViewPool(sharedRecycledViewPool)
        itemAnimator = ItemAnimator()
    }

    final override fun setRecycledViewPool(pool: RecycledViewPool?) =
        super.setRecycledViewPool(pool)

    override fun onSaveInstanceState() = SavedState(
        super.onSaveInstanceState(),
        (adapter as? Adapter<*, *>)?.onSaveInstanceState()
    )

    override fun onRestoreInstanceState(state: Parcelable?) {
        (state as? SavedState)?.let(::restoreSavedState) ?: super.onRestoreInstanceState(null)
    }

    private fun restoreSavedState(savedState: SavedState) {
        super.onRestoreInstanceState(savedState.superState)
        if (!canRestoreState) {
            pendingState = savedState
            return
        }
        (adapter as? Adapter<*, *>)?.apply { onRestoreInstanceState(savedState.adapterState) }
    }

    override fun setAdapter(adapter: RecyclerView.Adapter<*>?) {
        super.setAdapter(adapter)
        restorePendingState()
    }

    private fun beforeBindingAsNested() {
        detachItemAnimator()
    }

    private fun afterBindingAsNested() {
        attachItemAnimator()
        restorePendingState()
    }

    private fun detachItemAnimator() {
        itemAnimatorBackup = itemAnimator
        itemAnimator = null
    }

    private fun attachItemAnimator() {
        itemAnimatorBackup?.let {
            itemAnimatorBackup = null
            post { itemAnimator = it }
        }
    }

    private fun restorePendingState() {
        if (!canRestoreState) return
        pendingState?.let {
            pendingState = null
            restoreSavedState(it)
        }
    }

    private fun getRenewedViewHolder(oldViewHolder: ViewHolder) =
        children.mapNotNull { getChildViewHolder(it) as? ViewHolder }
            .firstOrNull { it.unstableItemId == oldViewHolder.unstableItemId && it !== oldViewHolder }

    private fun updateAdapter() {
        val list = list
        val getItemLayout = getItemLayout
        if (list == null || getItemLayout == null) {
            adapter = null
            return
        }
        if (adapter == null) adapter = DataBindingAdapter(getItemLayout)
        (adapter as? DataBindingAdapter)?.let {
            if (it.currentList !== list) it.submitList(list)
            if (it.getItemLayout !== getItemLayout) {
                adapter = null
                it.getItemLayout = getItemLayout
                adapter = it
            }
        }
    }

    private fun updateOnDataChangedCallback(list: List<*>?) {
        if (list is ObservableArrayList<*>) {
            if (onDataChangedCallback == null) onDataChangedCallback = OnDataChangedCallback(this)
            onDataChangedCallback?.data = list
        } else {
            onDataChangedCallback?.data = null
            onDataChangedCallback = null
        }
    }

    private fun updatePaginationScrollListener() {
        paginationScrollListener = if (getNextPage != null)
            PaginationScrollListener(this)
        else
            null.also { paginationScrollListener?.onRemoved() }
    }

    private fun updateItemIsFullyVisibleListener() {
        itemIsFullyVisibleListener = onItemIsFullyVisible?.let { ItemIsFullyVisibleListener(this) }
            ?: null.also { itemIsFullyVisibleListener?.onRemoved() }
    }

    interface GetItemLayout {
        operator fun invoke(itemPosition: Int, itemData: Any?): Int?
    }

    interface GetNextPageLoadingPosition {
        operator fun invoke(): Int?
    }

    interface GetNextPage

    interface SynchronousGetNextPage : GetNextPage {
        operator fun invoke(): List<Any?>?
    }

    interface GetNextPageOnCallback : GetNextPage {
        operator fun invoke(onSuccess: (List<Any?>?) -> Unit, onError: () -> Unit)
    }

    interface SuspendGetNextPage : GetNextPage {
        suspend operator fun invoke(): List<Any?>?
    }

    interface GetNextPageSingle : GetNextPage {
        operator fun invoke(): Single<List<Any?>>
    }

    interface OnViewHolderUpdated {
        operator fun invoke(viewHolder: RecyclerView.ViewHolder)
    }

    interface OnViewDataBindingHolderUpdated {
        operator fun invoke(viewHolder: ViewDataBindingHolder)
    }

    open class ItemAnimator : DefaultItemAnimator() {

        override fun canReuseUpdatedViewHolder(viewHolder: RecyclerView.ViewHolder) =
            if ((viewHolder as? ViewHolder)?.nestedRecyclerView != null)
                true
            else
                super.canReuseUpdatedViewHolder(viewHolder)
    }

    abstract class Adapter<T, VH : ViewHolder> : ListAdapter<T, VH>(getAsyncDifferConfig()) {

        lateinit var parent: EnhancedRecyclerView

        var actualList: List<T>? = null

        private var itemViewStates = ArrayList<ItemViewState>()

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            parent = recyclerView as EnhancedRecyclerView
        }

        @CallSuper
        override fun submitList(list: List<T>?) {
            submitList(list, null)
        }

        @CallSuper
        override fun submitList(list: List<T>?, commitCallback: Runnable?) {
            super.submitList(list) {
                actualList = list
                parent.apply {
                    updateOnDataChangedCallback(list)
                    lastPageSize = list?.size
                    if (isBindingAsNested) {
                        isBindingAsNested = false
                        afterBindingAsNested()
                    }
                }
                commitCallback?.run()
            }
        }

        private fun getUnstableItemId(position: Int) = (getItem(position) as? Identifiable)?.id

        @CallSuper
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.unstableItemId = getUnstableItemId(position)
            holder.nestedRecyclerView?.apply {
                setRecycledViewPool(this@Adapter.parent.sharedRecycledViewPool)
                beforeBindingAsNested()
                isBindingAsNested = true
            }
            restoreItemViewState(holder)
        }

        @CallSuper
        override fun onViewDetachedFromWindow(holder: VH) {
            saveItemViewState(holder)
        }

        private fun saveItemViewState(holder: ViewHolder) {
            if (holder.itemView.id == View.NO_ID) return

            //Достаем контейнер сохраненного состояния айтема и сохраняем состояние в него
            val itemId = holder.unstableItemId ?: return
            val itemViewState = itemViewStates.firstOrNull { it.itemId == itemId }
                ?: ItemViewState(itemId, SparseArray()).also { itemViewStates.add(it) }
            holder.itemView.saveHierarchyState(itemViewState.state)

            if (itemViewStates.size == parent.maxNumberOfItemsToSave) itemViewStates.removeAt(0)

            parent.getRenewedViewHolder(holder)
                ?.let { it.itemView.post { restoreItemViewState(it) } }
        }

        private fun restoreItemViewState(holder: ViewHolder) {
            if (holder.itemView.id == View.NO_ID) return

            //Накатываем сохраненное состояние на айтем и выкидываем его из целикового состояния адаптера
            val itemId = holder.unstableItemId ?: return
            val itemViewState = itemViewStates.firstOrNull { it.itemId == itemId } ?: return
            holder.itemView.restoreHierarchyState(itemViewState.state)
            itemViewStates.remove(itemViewState)
        }

        fun onSaveInstanceState(): Bundle {
            //Сохраняем состояние видимых айтемов в целиковое состояние адаптера и возвращаем его
            parent.children.forEach { (parent.getChildViewHolder(it) as? ViewHolder)?.let(::saveItemViewState) }
            return bundleOf(KEY_itemViewStates to itemViewStates)
        }

        fun onRestoreInstanceState(state: Parcelable?) {
            //Запоминаем целиковое состояние апаптера, чтобы накатить его на айтемы по отдельности после их привязки
            (state as? Bundle)?.let {
                state.classLoader = javaClass.classLoader
                itemViewStates =
                    state.getParcelableArrayList<ItemViewState>(KEY_itemViewStates) as? ArrayList<ItemViewState>
                        ?: return
            }
        }

        @Parcelize
        class ItemViewState(val itemId: @RawValue Any, val state: SparseArray<Parcelable>) :
            Parcelable

        companion object {
            private const val KEY_itemViewStates = "itemViewStates"

            fun <T> getAsyncDifferConfig() =
                AsyncDifferConfig.Builder<T>(DiffUtilCallback())
                    .setBackgroundThreadExecutor(
                        object : ThreadPoolExecutor(
                            2,
                            2,
                            0L,
                            TimeUnit.MILLISECONDS,
                            LinkedBlockingQueue()
                        ) {
                            override fun execute(command: Runnable) {
                                super.execute {
                                    try {
                                        command.run()
                                    } catch (e: Exception) {
                                    }
                                }
                            }
                        }
                    )
                    .build()

        }
    }

    abstract class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        var unstableItemId: Any? = null

        val nestedRecyclerView
            get() = itemView as? EnhancedRecyclerView
                ?: (itemView as? ViewGroup)?.getChildAt(0) as? EnhancedRecyclerView
    }

    private class DiffUtilCallback<T> : DiffUtil.ItemCallback<T>() {

        override fun areItemsTheSame(oldItem: T, newItem: T): Boolean =
            if (oldItem is Identifiable && newItem is Identifiable)
                oldItem.id == newItem.id
            else
                oldItem === newItem

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: T, newItem: T): Boolean =
            (oldItem as? Identifiable)?.contentEquals(newItem) ?: (oldItem == newItem)
    }

    private class DataBindingAdapter(var getItemLayout: GetItemLayout) :
        EnhancedRecyclerView.Adapter<Any?, ViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            getItemLayout(position, getItem(position)) ?: -1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            (ViewDataBindingHolder.create(parent, viewType) ?: EmptyViewHolder(parent.context))
                .also {
                    this.parent.onItemCreated?.invoke(it as? ViewDataBindingHolder ?: return@also)
                }

        fun ViewDataBindingHolder.Companion.create(
            parent: ViewGroup,
            viewType: Int
        ): ViewDataBindingHolder? {
            return ViewDataBindingHolder(
                DataBindingUtil.inflate(
                    LayoutInflater.from(parent.context),
                    viewType.takeIf { it != -1 } ?: return null,
                    parent,
                    false
                )
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            super.onBindViewHolder(holder, position)
            if (holder !is ViewDataBindingHolder) return
            holder.bind(position, getItem(position))
            parent.onItemBound?.invoke(holder)
        }

        override fun onViewRecycled(holder: ViewHolder) {
            parent.onItemRecycled?.invoke(holder as? ViewDataBindingHolder ?: return)
        }
    }

    class EmptyViewHolder(context: Context) :
        ViewHolder(View(context).apply { isGone = true })

    class ViewDataBindingHolder internal constructor(val itemBinding: ViewDataBinding) :
        ViewHolder(itemBinding.root) {

        fun bind(position: Int, data: Any?) {
            itemBinding.setVariable("itemPosition", position)

            itemBinding.setVariable("itemData", data)

            //Сразу привязываем изменения, чтобы не отрисовывался empty state
            itemBinding.executePendingBindings()
        }

        companion object
    }

    private class OnDataChangedCallback(private val parent: RecyclerView) :
        ObservableList.OnListChangedCallback<ObservableArrayList<*>>() {

        var data: ObservableArrayList<*>? = null
            set(value) {
                if (field === value) return
                field?.removeOnListChangedCallback(this)
                field = value
                value?.addOnListChangedCallback(this)
            }

        override fun onChanged(sender: ObservableArrayList<*>?) {
            parent.adapter?.notifyDataSetChanged()
        }

        override fun onItemRangeRemoved(
            sender: ObservableArrayList<*>?,
            positionStart: Int,
            itemCount: Int
        ) {
            parent.adapter?.notifyItemRangeRemoved(positionStart, itemCount)
        }

        override fun onItemRangeMoved(
            sender: ObservableArrayList<*>?,
            fromPosition: Int,
            toPosition: Int,
            itemCount: Int
        ) {
            parent.adapter?.notifyItemRangeChanged(min(fromPosition, toPosition), itemCount)
        }

        override fun onItemRangeInserted(
            sender: ObservableArrayList<*>?,
            positionStart: Int,
            itemCount: Int
        ) {
            parent.adapter?.notifyItemRangeInserted(positionStart, itemCount)
        }

        override fun onItemRangeChanged(
            sender: ObservableArrayList<*>?,
            positionStart: Int,
            itemCount: Int
        ) {
            parent.adapter?.notifyItemRangeChanged(positionStart, itemCount)
        }
    }

    private class PaginationScrollListener(private val parent: EnhancedRecyclerView) {

        @Volatile
        private var pageIsLoading = false

        @Volatile
        private var allPagesAreLoaded = false

        var getNextPageLoadingPosition: GetNextPageLoadingPosition? = null

        private val coroutineScope = ClearableCoroutineScope(Dispatchers.IO)

        private val disposables = CompositeDisposable()

        private val onScrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (allPagesAreLoaded || pageIsLoading) return
                val lastVisibleItemPosition =
                    ((recyclerView.layoutManager as? LinearLayoutManager) ?: return)
                        .findLastVisibleItemPosition()
                val loadPosition = getNextPageLoadingPosition?.let { it() ?: return }
                    ?: getDefaultLoadingPosition() ?: return
                if (lastVisibleItemPosition >= loadPosition) loadPage()
            }
        }

        private val onAttachStateChangeListener = object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View?) = Unit

            override fun onViewDetachedFromWindow(v: View?) {
                coroutineScope.clear()
                disposables.clear()
            }
        }

        init {
            parent.addOnScrollListener(onScrollListener)
            parent.addOnAttachStateChangeListener(onAttachStateChangeListener)
        }

        private fun getDefaultLoadingPosition(): Int? {
            return (parent.adapter ?: return null).itemCount -
                    1 -
                    (parent.lastPageSize ?: return null) / 2
        }

        private fun loadPage() {
            pageIsLoading = true
            when (val getNextPage = parent.getNextPage) {
                is SynchronousGetNextPage -> try {
                    onPageLoaded(getNextPage())
                } catch (e: Exception) {
                } finally {
                    pageIsLoading = false
                }
                is GetNextPageOnCallback -> getNextPage(
                    onSuccess = { onPageLoaded(it) },
                    onError = { pageIsLoading = false }
                )
                is SuspendGetNextPage -> coroutineScope.launch {
                    try {
                        val page = getNextPage()
                        withContext(Dispatchers.Main) { onPageLoaded(page) }
                    } catch (e: Exception) {
                    } finally {
                        pageIsLoading = false
                    }
                }
                is GetNextPageSingle -> disposables.add(
                    getNextPage().subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .doFinally { pageIsLoading = false }
                        .subscribe({ onPageLoaded(it) }) {}
                )
            }
        }

        private fun onPageLoaded(page: List<Any?>?) {
            pageIsLoading = false
            val data =
                ((parent.adapter as? Adapter<*, *>)?.actualList as? MutableList<Any?>) ?: return
            if (!page.isNullOrEmpty()) {
                data.addAll(page)
                parent.lastPageSize = page.size
                if (data !is ObservableArrayList)
                    parent.adapter?.notifyItemRangeInserted(data.size - page.size, page.size)
            } else {
                allPagesAreLoaded = true
            }
        }

        fun onRemoved() {
            parent.removeOnScrollListener(onScrollListener)
            parent.removeOnAttachStateChangeListener(onAttachStateChangeListener)
        }
    }

    class ItemIsFullyVisibleListener(private val parent: EnhancedRecyclerView) {

        private val fullyVisibleItems = HashSet<View>()

        private val onChildAttachStateChangeListener =
            object : OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    notifyItemIsFullyVisible(view)
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    fullyVisibleItems.remove(view)
                }
            }

        private val onScrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) =
                recyclerView.children.forEach { notifyItemIsFullyVisible(it) }
        }

        init {
            parent.addOnChildAttachStateChangeListener(onChildAttachStateChangeListener)
            parent.addOnScrollListener(onScrollListener)
        }

        private fun notifyItemIsFullyVisible(itemView: View) {
            if (!itemView.isInParentBounds) {
                fullyVisibleItems.remove(itemView)
                return
            }
            if (fullyVisibleItems.contains(itemView)) return
            fullyVisibleItems.add(itemView)
            parent.onItemIsFullyVisible?.invoke(parent.getChildViewHolder(itemView))
        }

        fun onRemoved() {
            parent.removeOnChildAttachStateChangeListener(onChildAttachStateChangeListener)
            parent.removeOnScrollListener(onScrollListener)
        }
    }

    @Parcelize
    class SavedState(val superState: Parcelable?, val adapterState: Parcelable?) : Parcelable

    companion object {

        @JvmStatic
        @BindingAdapter("getItemLayout")
        fun setGetItemLayout(view: EnhancedRecyclerView, value: GetItemLayout?) {
            view.getItemLayout = value
        }

        @JvmStatic
        @BindingAdapter("list")
        fun setList(view: EnhancedRecyclerView, value: List<*>?) {
            view.list = value
        }

        @JvmStatic
        @BindingAdapter("synchronousGetNextPage")
        fun setSynchronousGetNextPage(view: EnhancedRecyclerView, value: SynchronousGetNextPage?) {
            value?.let { view.getNextPage = it }
                ?: (view.getNextPage as? SynchronousGetNextPage)?.also { view.getNextPage = null }
        }

        @JvmStatic
        @BindingAdapter("getNextPageOnCallback")
        fun setGetNextPageOnCallback(view: EnhancedRecyclerView, value: GetNextPageOnCallback?) {
            value?.let { view.getNextPage = it }
                ?: (view.getNextPage as? GetNextPageOnCallback)?.also { view.getNextPage = null }
        }

        @JvmStatic
        @BindingAdapter("suspendGetNextPage")
        fun setSuspendGetNextPage(view: EnhancedRecyclerView, value: SuspendGetNextPage?) {
            value?.let { view.getNextPage = it }
                ?: (view.getNextPage as? SuspendGetNextPage)?.also { view.getNextPage = null }
        }

        @JvmStatic
        @BindingAdapter("getNextPageSingle")
        fun setGetNextPageSingle(view: EnhancedRecyclerView, value: GetNextPageSingle?) {
            value?.let { view.getNextPage = it }
                ?: (view.getNextPage as? GetNextPageSingle)?.also { view.getNextPage = null }
        }

        @JvmStatic
        @BindingAdapter("onItemCreated")
        fun setOnItemCreated(
            view: EnhancedRecyclerView,
            value: OnViewDataBindingHolderUpdated
        ) {
            view.onItemCreated = value
        }

        @JvmStatic
        @BindingAdapter("onItemBound")
        fun setOnItemBound(
            view: EnhancedRecyclerView,
            value: OnViewDataBindingHolderUpdated
        ) {
            view.onItemBound = value
        }

        @JvmStatic
        @BindingAdapter("onItemRecycled")
        fun setOnItemRecycled(
            view: EnhancedRecyclerView,
            value: OnViewDataBindingHolderUpdated
        ) {
            view.onItemRecycled = value
        }

        @JvmStatic
        @BindingAdapter("onItemIsFullyVisible")
        fun setOnItemIsFullyVisible(
            view: EnhancedRecyclerView,
            value: OnViewHolderUpdated
        ) {
            view.onItemIsFullyVisible = value
        }
    }
}