package io.ethan.pushgo.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

data class AppCoroutineDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
)
