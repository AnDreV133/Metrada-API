package com.metrada.model

import com.fasterxml.jackson.annotation.JsonInclude

//data class MetricResponse(
//    val status: String = "success",
//    val data: MetricData
//)
//
//@JsonInclude(JsonInclude.Include.NON_NULL)
//data class MetricData(
//    val resultType: String, // "matrix", "vector", "scalar", "string"
//    val result: Any
//)
//
//@JsonInclude(JsonInclude.Include.NON_EMPTY)
//data class VectorResult(
//    val metric: Map<String, String> = emptyMap(),
//    val value: List<Any> // [timestamp (Long), value (String)]
//)
//
//@JsonInclude(JsonInclude.Include.NON_EMPTY)
//data class MatrixResult(
//    val metric: Map<String, String> = emptyMap(),
//    val values: List<List<Any>> // [[timestamp, value], ...]
//)


@JsonInclude(JsonInclude.Include.NON_NULL)
data class MetricResponseModel(
    val status: String,          // "success" или "error"
    val data: MetricDataModel? = null,
    val error: String? = null,
    val errorType: String? = null
)

data class MetricDataModel(
    val resultType: String,      // "vector", "matrix", "scalar", "string"
    val result: List<Any>
)

// Для instant query (vector)
data class VectorResultModel(
    val metric: Map<String, String>,
    val value: List<Any>         // [timestamp_seconds, value_string]
)

// Для range query (matrix)
data class MatrixResultModel(
    val metric: Map<String, String>,
    val values: List<List<Any>>  // [[timestamp_seconds, value_string], ...]
)