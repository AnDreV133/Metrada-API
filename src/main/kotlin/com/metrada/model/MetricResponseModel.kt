package com.metrada.model

import com.fasterxml.jackson.annotation.JsonInclude

data class MetricResponse(
    val status: String = "success",
    val data: MetricData
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MetricData(
    val resultType: String, // "matrix", "vector", "scalar", "string"
    val result: Any
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class VectorResult(
    val metric: Map<String, String> = emptyMap(),
    val value: List<Any> // [timestamp (Long), value (String)]
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class MatrixResult(
    val metric: Map<String, String> = emptyMap(),
    val values: List<List<Any>> // [[timestamp, value], ...]
)