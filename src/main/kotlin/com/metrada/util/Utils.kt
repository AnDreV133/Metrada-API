package com.metrada.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

fun <T> Flow<T>.chunked(size: Int): Flow<List<T>> = flow {
    val chunkedList = mutableListOf<T>()
    this@chunked.collect { value ->
        chunkedList.add(value)
        if (chunkedList.size == size) {
            emit(chunkedList.toList())
            chunkedList.clear()
        }
    }
    if (chunkedList.isNotEmpty()) emit(chunkedList.toList())
}

