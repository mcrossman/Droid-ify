package com.looker.droidify.ui.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.DataSource
import androidx.paging.LivePagedListBuilder
import androidx.paging.PagedList
import com.looker.droidify.content.Preferences
import com.looker.droidify.database.DatabaseX
import com.looker.droidify.database.entity.Product
import com.looker.droidify.entity.ProductItem
import com.looker.droidify.ui.fragments.Request
import com.looker.droidify.ui.fragments.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainNavFragmentViewModelX(val db: DatabaseX, primarySource: Source, secondarySource: Source) :
    ViewModel() {

    private val _order = MutableStateFlow(ProductItem.Order.LAST_UPDATE)
    private val _sections = MutableStateFlow<ProductItem.Section>(ProductItem.Section.All)
    private val _searchQuery = MutableStateFlow("")

    val order: StateFlow<ProductItem.Order> = _order.stateIn(
        initialValue = ProductItem.Order.LAST_UPDATE,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    val sections: StateFlow<ProductItem.Section> = _sections.stateIn(
        initialValue = ProductItem.Section.All,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )
    val searchQuery: StateFlow<String> = _searchQuery.stateIn(
        initialValue = "",
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    fun request(source: Source): Request {
        var mSearchQuery = ""
        var mSections: ProductItem.Section = ProductItem.Section.All
        var mOrder: ProductItem.Order = ProductItem.Order.NAME
        viewModelScope.launch {
            launch { searchQuery.collect { if (source.sections) mSearchQuery = it } }
            launch { sections.collect { if (source.sections) mSections = it } }
            launch { order.collect { if (source.order) mOrder = it } }
        }
        return when (source) {
            Source.AVAILABLE -> Request.ProductsAll(
                mSearchQuery,
                mSections,
                mOrder
            )
            Source.INSTALLED -> Request.ProductsInstalled(
                mSearchQuery,
                mSections,
                mOrder
            )
            Source.UPDATES -> Request.ProductsUpdates(
                mSearchQuery,
                mSections,
                mOrder
            )
            // TODO differentiate between updated and new (e.g. number of releases)
            Source.UPDATED -> Request.ProductsUpdated(
                mSearchQuery,
                mSections,
                ProductItem.Order.LAST_UPDATE,
                Preferences[Preferences.Key.UpdatedApps]
            )
            Source.NEW -> Request.ProductsNew(
                mSearchQuery,
                mSections,
                ProductItem.Order.LAST_UPDATE,
                Preferences[Preferences.Key.NewApps]
            )
        }
    }

    private val pagedListConfig by lazy {
        PagedList.Config.Builder()
            .setPageSize(30)
            .setPrefetchDistance(30)
            .setEnablePlaceholders(false)
            .build()
    }
    private val primaryRequest = request(primarySource)
    val primaryProducts: LiveData<PagedList<Product>> by lazy {
        LivePagedListBuilder(
            db.productDao.queryList(
                installed = primaryRequest.installed,
                updates = primaryRequest.updates,
                searchQuery = primaryRequest.searchQuery,
                section = primaryRequest.section,
                order = primaryRequest.order,
                numberOfItems = primaryRequest.numberOfItems
            ), pagedListConfig
        ).build()
    }
    private val secondaryRequest = request(secondarySource)
    val secondaryProducts: LiveData<PagedList<Product>> by lazy {
        LivePagedListBuilder(
            db.productDao.queryList(
                installed = secondaryRequest.installed,
                updates = secondaryRequest.updates,
                searchQuery = secondaryRequest.searchQuery,
                section = secondaryRequest.section,
                order = secondaryRequest.order,
                numberOfItems = secondaryRequest.numberOfItems
            ), pagedListConfig
        ).build()
    }

    fun fillList(source: Source) {
        viewModelScope.launch(Dispatchers.Default) {
            // productsList = query(request(source))
        }
    }

    private suspend fun query(request: Request): DataSource.Factory<Int, Product> {
        return withContext(Dispatchers.Default) {
            db.productDao
                .queryList(
                    installed = request.installed,
                    updates = request.updates,
                    searchQuery = request.searchQuery,
                    section = request.section,
                    order = request.order,
                    numberOfItems = request.numberOfItems
                )
        }
    }

    fun setSection(newSection: ProductItem.Section, perform: () -> Unit) {
        viewModelScope.launch {
            if (newSection != sections.value) {
                _sections.emit(newSection)
                launch(Dispatchers.Main) { perform() }
            }
        }
    }

    fun setOrder(newOrder: ProductItem.Order, perform: () -> Unit) {
        viewModelScope.launch {
            if (newOrder != order.value) {
                _order.emit(newOrder)
                launch(Dispatchers.Main) { perform() }
            }
        }
    }

    fun setSearchQuery(newSearchQuery: String, perform: () -> Unit) {
        viewModelScope.launch {
            if (newSearchQuery != searchQuery.value) {
                _searchQuery.emit(newSearchQuery)
                launch(Dispatchers.Main) { perform() }
            }
        }
    }

    class Factory(
        val db: DatabaseX,
        private val primarySource: Source,
        private val secondarySource: Source
    ) :
        ViewModelProvider.Factory {
        @Suppress("unchecked_cast")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainNavFragmentViewModelX::class.java)) {
                return MainNavFragmentViewModelX(db, primarySource, secondarySource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
