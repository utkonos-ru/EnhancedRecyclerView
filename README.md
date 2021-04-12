**EnhancedRecyclerView** is an improved version of Android RecyclerView, highly optimized internally for the best performance.
It also provides functionality that allows you to implement various common tasks with a minimum amount of code.

## Installation

In your root build.gradle:
```gradle
allprojects {
   repositories {
      ...
      maven { url 'https://jitpack.io' }
   }
}
```
In your app/build.gradle:
```gradle
dependencies {
   implementation 'com.github.utkonos-online-shop.enhanced-recycler-view:1.0.0'
}
```

## Implementation

In your layout.xml:
```xml
<ru.utkonos.enhanced_recycler_vew.EnhancedRecyclerView
    android:id="@+id/recycler_view"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

Then in code:
```kotlin
class MyAdapter : EnhancedRecyclerView.Adapter<MyItemDataClass, MyItemViewHolder>() {
    ...
}
...
val adapter = MyAdapter()
adapter.submitList(myDataList)
recycler_view.setAdapter(adapter)
```

**Or you can write no code at all** if you [make the implementation using data binding]()

## Features

### Full state saving
EnhancedRecyclerView automatically saves and restores its scroll position during `onSaveInstanceState` and `onRestoreInstanceState`. Morover, it also saves the state of its items. For example, the scroll position of a nested RecyclerView will also be automatically saved.

The state of an item is saved both during `onSaveInstanceState` and when scrolling away from that item. In this case, when scrolling back to that item, its state is restored.

For item state saving to work, the corresponding item data class [must be identifiable](). Also do not forget to set id to your item view.

### High performance for nested RecyclerViews
EnhancedRecyclerView allows you to create nested RecyclerViews that work perfectly without any artifacts. You don't need to write any special code for this, just use EnhancedRecyclerView as your nested RecyclerView.

### Observing data list updates
EnhancedRecyclerView can observe its data list. In this case, when the list is updated, the corresponding updates will be made in the UI. For example, if you call `myDataList.remove(item)`, the corresponding item will be immediately removed from the screen, and you don't need to call `adapter.notifyItemRemoved(position)`.

For this functionality to work, your data list must be `androidx.databinding.ObservableArrayList`.

###  DiffUtils
In EnhancedRecyclerView, DiffUtils are enabled by default. For it to work correctly, the only thing you need to do is [make your elements identifiable]().

### Pagination
EnhancedRecyclerView allows you to do pagination in a very simple way. All you need to do is set an interface for loading next page. There are several types of these interfaces, so you can choose the one that best suits the data loading logic in your application. Here are examples of all their implementations:
```kotlin
object : SynchronousGetNextPage {
    override fun invoke(): List<Any?>? {}
}
object : GetNextPageOnCallback {
    override fun invoke(callback: (List<Any?>?) -> Unit) {}
}
object : SuspendGetNextPage {
    override suspend fun invoke(): List<Any?>? {}
}
object : GetNextPageSingle {
    //io.reactivex.Single
    override fun invoke(): Single<List<Any?>> {}
}
```
To use any of them, just call:
```koltin
recycler_view.getNextPage = myGetNextPage
```
Your interface will be automatically called when you scroll to the middle of the last page.

### DataBinding support
EnhancedRecyclerView has a unique and most modern way of initialization, without writing any code at all. That is, you do not need to create an adapter and call methods on the RecyclerView. This is achieved through [Android DataBinding](). All you have to do is set two attributes in xml: `list` and `getItemLayout`:
```xml
<ru.utkonos.enhanced_recycler_vew.EnhancedRecyclerView
    getItemLayout="@{(itemPosition, itemData) -> @layout/layout_my_item}"
    list="@{myDataList}"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```
`layout_my_item.xml`:
```xml
<layout>
    <data>
        <variable name="itemPosition" type="Integer" />
        <variable name="itemData" type="MyItemDataClass" />
    </data>
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@{itemData.text}" />
</layout
```
How it works:
1. You set your data list to the `list` attribute
1. You return your item's layout resource in `getItemLayout` function, based on `itemPosition` and your data list item under that position (`itemData`)
2. The adapter is generated automatically based on the bound data
3. In your item layout xml, you create two variables (each is optional): `itemPosition` and `itemData`. And in `onBindViewHolder` of the generated Adapter, the corresponding values will be set to these variables
### Item identification
For different pupuses EnhancedRecyclerView needs to identificate its items. For this identification to work, you need to implement `ru.utkonos.enhanced_recycler_vew.Identifiable` to your item data classes:
```kotlin
class MyItemDataClass(override val id: Any): Identifiable
```
The id must be unque in list and its type should be one of: primitive, String, Parcelable, Serializable.

If you do not have any explicit identifier for an item and there are no other instances of its class in the list, you can implement `ru.utkonos.enhanced_recycler_vew.IdentifiableByClass` to it:
```kotlin
class MyItemDataClass: IdentifiableByClass
```
Then its id will be its class.