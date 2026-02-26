package com.happyhealth.bleplatform.internal.command

fun readUInt32(buf: ByteArray, offset: Int): UInt {
    val b0 = buf[offset + 0].toUByte().toUInt()
    val b1 = buf[offset + 1].toUByte().toUInt()
    val b2 = buf[offset + 2].toUByte().toUInt()
    val b3 = buf[offset + 3].toUByte().toUInt()
    return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
}

fun readUInt16(buf: ByteArray, offset: Int): UInt {
    val b0 = buf[offset + 0].toUByte().toUInt()
    val b1 = buf[offset + 1].toUByte().toUInt()
    return (b1 shl 8) or b0
}

fun writeUInt16(buf: ByteArray, offset: Int, value: UShort) {
    buf[offset + 0] = (value.toUInt() shr 0).toByte()
    buf[offset + 1] = (value.toUInt() shr 8).toByte()
}

fun writeUInt32(buf: ByteArray, offset: Int, value: UInt) {
    buf[offset + 0] = (value shr 0).toByte()
    buf[offset + 1] = (value shr 8).toByte()
    buf[offset + 2] = (value shr 16).toByte()
    buf[offset + 3] = (value shr 24).toByte()
}

fun ByteArray.toHex(): String =
    joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

internal fun ByteArray.ubyte(i: Int): Int =
    if (i < size) this[i].toUByte().toInt() else 0

internal fun ByteArray.bool(i: Int): Boolean =
    if (i < size) this[i].toUByte().toInt() != 0 else false

internal fun ByteArray.u32(i: Int): UInt =
    if (i + 3 < size) readUInt32(this, i) else 0u
