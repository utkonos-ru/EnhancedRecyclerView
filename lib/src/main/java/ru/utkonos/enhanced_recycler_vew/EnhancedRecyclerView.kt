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
import kotlin.math.min

class EnhancedRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    var maxNumberOfItemsToSave = 30

    private var pendingState: SavedState? = null

    @Volatile
    private var isBindingAsNested = false

    private val canRestoreState get() = !isBindingAsNested && adapter != null

    private var itemAnimatorBackup: ItemAnimator? = null

    private var items: List<Any?>? = null
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

    var getNextPage: GetNextPage? = null
        set(value) {
            if (field === value) return
            field = value
            updatePaginationScrollListener()
        }

    private var paginationScrollListener: PaginationScrollListener? = null

    private var pageSize: Int? = null

    var onItemBound: OnItemLifecycleEvent? = null

    var onItemRecycled: OnItemLifecycleEvent? = null

    var onItemIsFullyVisible: OnItemLifecycleEvent? = null
        set(value) {
            if (field === value) return
            field = value
            updateItemIsFullyVisibleListener()
        }

    private var itemIsFullyVisibleListener: ItemIsFullyVisibleListener? = null

    private val coroutineScope = ClearableCoroutineScope(Dispatchers.IO)

    private val disposables = CompositeDisposable()

    init {
        itemAnimator = object : DefaultItemAnimator() {
            override fun canReuseUpdatedViewHolder(viewHolder: RecyclerView.ViewHolder) = true
        }
    }

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
        val items = items
        val getItemLayout = getItemLayout
        if (items == null || getItemLayout == null) {
            adapter = null
            return
        }
        if (adapter == null) adapter = DataBindingAdapter(getItemLayout)
        (adapter as? DataBindingAdapter)?.let {
            if (it.currentList !== items) it.submitList(items)
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
        paginationScrollListener = getNextPage?.let { PaginationScrollListener(this) }
    }

    private fun updateItemIsFullyVisibleListener() {
        itemIsFullyVisibleListener = onItemIsFullyVisible?.let { ItemIsFullyVisibleListener(this) }
            ?: null.also { itemIsFullyVisibleListener?.onRemoved() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        coroutineScope.clear()
        disposables.clear()
    }

    interface GetItemLayout {
        operator fun invoke(itemPosition: Int, itemData: Any?): Int?
    }

    interface GetNextPage

    interface SynchronousGetNextPage : GetNextPage {
        operator fun invoke(): List<Any?>?
    }

    interface GetNextPageOnCallback : GetNextPage {
        operator fun invoke(callback: (List<Any?>?) -> Unit)
    }

    interface SuspendGetNextPage : GetNextPage {
        suspend operator fun invoke(): List<Any?>?
    }

    interface GetNextPageSingle : GetNextPage {
        operator fun invoke(): Single<List<Any?>>
    }

    interface OnItemLifecycleEvent {
        operator fun invoke(viewHolder: RecyclerView.ViewHolder)
    }

    abstract class Adapter<T, VH : ViewHolder> : ListAdapter<T, VH>(DiffUtilCallback()) {

        private lateinit var parent: EnhancedRecyclerView

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
                parent.apply {
                    updateOnDataChangedCallback(list)
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
                beforeBindingAsNested()
                isBindingAsNested = true
            }
            restoreItemViewState(holder)
            parent.onItemBound?.invoke(holder)
        }

        override fun onViewRecycled(holder: VH) {
            parent.onItemRecycled?.invoke(holder)
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
            oldItem == newItem
    }

    private class DataBindingAdapter(var getItemLayout: GetItemLayout) :
        EnhancedRecyclerView.Adapter<Any?, ViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            getItemLayout(position, getItem(position)) ?: -1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            (ViewDataBindingHolder.create(parent, viewType) ?: EmptyViewHolder(parent.context))

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
        }
    }

    class EmptyViewHolder(context: Context) :
        ViewHolder(View(context).apply { isGone = true })

    class ViewDataBindingHolder(private val itemBinding: ViewDataBinding) :
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

    private class PaginationScrollListener(private val parent: EnhancedRecyclerView) :
        RecyclerView.OnScrollListener() {

        @Volatile
        private var pageIsLoading = false

        @Volatile
        private var allPagesAreLoaded = false

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (allPagesAreLoaded || pageIsLoading) return
            val lastVisibleItemPosition =
                ((recyclerView.layoutManager as? LinearLayoutManager) ?: return)
                    .findLastVisibleItemPosition()
            val loadPosition =
                (recyclerView.adapter ?: return).itemCount - 1 - (parent.pageSize ?: return) / 2
            if (lastVisibleItemPosition >= loadPosition) loadPage()
        }

        private fun loadPage() {
            pageIsLoading = true
            when (val getMoreData = parent.getNextPage) {
                is SynchronousGetNextPage -> onPageLoaded(getMoreData())
                is GetNextPageOnCallback -> getMoreData { onPageLoaded(it) }
                is SuspendGetNextPage -> parent.coroutineScope.launch {
                    try {
                        val page = getMoreData()
                        withContext(Dispatchers.Main) { onPageLoaded(page) }
                    } catch (e: Exception) {
                    }
                }
                is GetNextPageSingle -> parent.disposables.add(
                    getMoreData().subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe({ onPageLoaded(it) }) {}
                )
            }
        }

        private fun onPageLoaded(page: List<Any?>?) {
            val data = ((parent.adapter as? ListAdapter<*, *>)?.currentList as? MutableList<Any?>)
                ?: return
            if (!page.isNullOrEmpty()) {
                data.addAll(page)
                parent.pageSize = page.size
                if (data !is ObservableArrayList)
                    parent.adapter?.notifyItemRangeInserted(data.size - page.size, page.size)
            } else {
                allPagesAreLoaded = true
            }
            pageIsLoading = false
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
        @BindingAdapter("items")
        fun setItems(view: EnhancedRecyclerView, value: List<*>?) {
            view.items = value
        }

        @JvmStatic
        @BindingAdapter("synchronousGetNextPage")
        fun setSynchronousGetNextPage(view: EnhancedRecyclerView, value: SynchronousGetNextPage?) {
            view.getNextPage = value
        }

        @JvmStatic
        @BindingAdapter("getNextPageOnCallback")
        fun setGetNextPageOnCallback(view: EnhancedRecyclerView, value: GetNextPageOnCallback?) {
            view.getNextPage = value
        }

        @JvmStatic
        @BindingAdapter("suspendGetNextPage")
        fun setSuspendGetNextPage(view: EnhancedRecyclerView, value: SuspendGetNextPage?) {
            view.getNextPage = value
        }

        @JvmStatic
        @BindingAdapter("getNextPageSingle")
        fun setGetNextPageSingle(view: EnhancedRecyclerView, value: GetNextPageSingle?) {
            view.getNextPage = value
        }

        @JvmStatic
        @BindingAdapter("onItemBound")
        fun setOnItemBound(
            view: EnhancedRecyclerView,
            value: OnItemLifecycleEvent
        ) {
            view.onItemBound = value
        }

        @JvmStatic
        @BindingAdapter("onItemRecycled")
        fun setOnItemRecycled(
            view: EnhancedRecyclerView,
            value: OnItemLifecycleEvent
        ) {
            view.onItemRecycled = value
        }

        @JvmStatic
        @BindingAdapter("onItemIsFullyVisible")
        fun setOnItemIsFullyVisible(
            view: EnhancedRecyclerView,
            value: OnItemLifecycleEvent
        ) {
            view.onItemIsFullyVisible = value
        }
    }
}