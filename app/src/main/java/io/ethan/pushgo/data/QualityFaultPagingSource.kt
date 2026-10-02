package io.ethan.pushgo.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.ethan.pushgo.testing.QualityRuntime
import kotlinx.coroutines.CancellationException

/**
 * Debug quality sessions exercise Paging's real loading and error contracts.
 * Production sessions are a zero-fault pass-through because QualityRuntime
 * cannot resolve a quality profile from a release build.
 */
internal class QualityFaultPagingSource<Key : Any, Value : Any>(
    private val delegate: PagingSource<Key, Value>,
    private val beforeLoad: suspend (PagingSource.LoadParams<Key>) -> Unit = { params ->
        if (params is PagingSource.LoadParams.Append) {
            QualityRuntime.beforeMessagePageLoad()
        } else {
            QualityRuntime.beforeMessageListLoad()
        }
    },
) : PagingSource<Key, Value>() {
    init {
        registerInvalidatedCallback(delegate::invalidate)
        delegate.registerInvalidatedCallback(::invalidate)
    }

    override fun getRefreshKey(state: PagingState<Key, Value>): Key? = delegate.getRefreshKey(state)

    override suspend fun load(params: LoadParams<Key>): LoadResult<Key, Value> {
        return try {
            beforeLoad(params)
            if (params is LoadParams.Refresh) {
                QualityRuntime.beforeMessageRefreshPresentation()
            }
            delegate.load(params)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            LoadResult.Error(error)
        }
    }
}
