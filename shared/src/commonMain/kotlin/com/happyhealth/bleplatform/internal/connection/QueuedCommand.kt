package com.happyhealth.bleplatform.internal.connection

import com.happyhealth.bleplatform.internal.model.HpyCharId

data class QueuedCommand(
    val tag: String,
    val charId: HpyCharId,
    val data: ByteArray,
    val timeoutMs: Long,
    val completionType: CompletionType,
)
