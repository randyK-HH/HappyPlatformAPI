package com.happyhealth.bleplatform.api

sealed class HpyResult {
    data object Ok : HpyResult()
    data object ErrInvalidConnId : HpyResult()
    data object ErrNotConnected : HpyResult()
    data object ErrQueueFull : HpyResult()
    data object ErrCommandRejected : HpyResult()
    data object ErrFwNotSupported : HpyResult()
    data object ErrMaxConnections : HpyResult()
}
