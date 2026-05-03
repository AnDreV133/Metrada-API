package com.metrada.service.parser

//interface IQuerier {
//    // tsdb: принимает метки, диапазон времени (мс), возвращает итератор по сериям
//    suspend fun select(mint: Long, maxt: Long, matchers: List<LabelMatcher>): ISeriesSet
//}
//
interface ISeriesSet {
    suspend fun next(): Boolean
    fun at(): IStorageSeries
    fun warnings(): List<Throwable>
    fun error(): Throwable?
}

interface IStorageSeries {
    fun labels(): Labels
    fun iterator(): ISeriesIterator
}

interface ISeriesIterator {
    fun seek(ts: Long): Boolean   // перейти к первой точке с ts >= заданного
    fun next(): Boolean
    fun at(): FPoint
    fun error(): Throwable?
}