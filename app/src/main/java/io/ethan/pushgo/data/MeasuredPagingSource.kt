package io.ethan.pushgo.data

import android.os.SystemClock
import androidx.paging.PagingSource
import androidx.paging.PagingState

class MeasuredPagingSource<Key : Any, Value : Any>(
    private val delegate: PagingSource<Key, Value>,
    private val onLoad: suspend (Long) -> Unit,
) : PagingSource<Key, Value>() {
    init {
        delegate.registerInvalidatedCallback { invalidate() }
    }

    override suspend fun load(params: LoadParams<Key>): LoadResult<Key, Value> {
        val start = SystemClock.elapsedRealtime()
        val result = delegate.load(params)
        val elapsed = SystemClock.elapsedRealtime() - start
        onLoad(elapsed)
        return result
    }

    override fun getRefreshKey(state: PagingState<Key, Value>): Key? {
        return delegate.getRefreshKey(state)
    }
}
