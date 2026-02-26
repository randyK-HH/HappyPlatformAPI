package com.happyhealth.bleplatform.api

@JvmInline
value class ConnectionId(val value: Int) {
    init {
        require(value in -1..7) { "ConnectionId must be -1..7, got $value" }
    }

    override fun toString(): String = "conn$value"

    companion object {
        val INVALID = ConnectionId(-1)
    }
}
