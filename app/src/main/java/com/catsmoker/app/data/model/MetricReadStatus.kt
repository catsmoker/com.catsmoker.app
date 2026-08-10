package com.catsmoker.app.data.model

enum class MetricReadStatus {
    Loading,
    Ok,
    NoRoot,
    EmptyOutput,
    ParseFailed,
    NoData,
    Stale
}
