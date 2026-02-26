package com.happyhealth.bleplatform.internal.shim

import com.happyhealth.bleplatform.internal.command.UuidConstants
import com.happyhealth.bleplatform.internal.model.HpyCharId
import java.util.UUID

object CharacteristicMap {

    data class CharLocation(val serviceUuid: UUID, val charUuid: UUID)

    private val charIdToUuid: Map<HpyCharId, CharLocation> = mapOf(
        // HCS Service
        HpyCharId.CMD_RX to CharLocation(
            UUID.fromString(UuidConstants.UUID_HPY_HCS),
            UUID.fromString(UuidConstants.UUID_HPY_CMD_RX),
        ),
        HpyCharId.CMD_TX to CharLocation(
            UUID.fromString(UuidConstants.UUID_HPY_HCS),
            UUID.fromString(UuidConstants.UUID_HPY_CMD_TX),
        ),
        HpyCharId.STREAM_TX to CharLocation(
            UUID.fromString(UuidConstants.UUID_HPY_HCS),
            UUID.fromString(UuidConstants.UUID_HPY_STREAM_TX),
        ),
        HpyCharId.DEBUG_TX to CharLocation(
            UUID.fromString(UuidConstants.UUID_HPY_HCS),
            UUID.fromString(UuidConstants.UUID_HPY_DEBUG_TX),
        ),
        HpyCharId.FRAME_TX to CharLocation(
            UUID.fromString(UuidConstants.UUID_HPY_HCS),
            UUID.fromString(UuidConstants.UUID_HPY_FRAME_TX),
        ),
        // DIS
        HpyCharId.DIS_SERIAL_NUMBER to CharLocation(
            UUID.fromString(UuidConstants.UUID_SERVICE_DEVICE_INFO),
            UUID.fromString(UuidConstants.UUID_CHAR_SERIAL_NUMBER),
        ),
        HpyCharId.DIS_FW_VERSION to CharLocation(
            UUID.fromString(UuidConstants.UUID_SERVICE_DEVICE_INFO),
            UUID.fromString(UuidConstants.UUID_CHAR_FW_VERSION),
        ),
        HpyCharId.DIS_SW_VERSION to CharLocation(
            UUID.fromString(UuidConstants.UUID_SERVICE_DEVICE_INFO),
            UUID.fromString(UuidConstants.UUID_CHAR_SW_VERSION),
        ),
        HpyCharId.DIS_MANUFACTURER_NAME to CharLocation(
            UUID.fromString(UuidConstants.UUID_SERVICE_DEVICE_INFO),
            UUID.fromString(UuidConstants.UUID_CHAR_MANUFACTURER_NAME),
        ),
        HpyCharId.DIS_MODEL_NUMBER to CharLocation(
            UUID.fromString(UuidConstants.UUID_SERVICE_DEVICE_INFO),
            UUID.fromString(UuidConstants.UUID_CHAR_MODEL_NUMBER),
        ),
        // SUOTA
        HpyCharId.SUOTA_MEM_DEV to CharLocation(
            UUID.fromString(UuidConstants.UUID_SUOTA_SERVICE),
            UUID.fromString(UuidConstants.UUID_SUOTA_MEM_DEV),
        ),
        HpyCharId.SUOTA_GPIO_MAP to CharLocation(
            UUID.fromString(UuidConstants.UUID_SUOTA_SERVICE),
            UUID.fromString(UuidConstants.UUID_SUOTA_GPIO_MAP),
        ),
        HpyCharId.SUOTA_MEM_INFO to CharLocation(
            UUID.fromString(UuidConstants.UUID_SUOTA_SERVICE),
            UUID.fromString(UuidConstants.UUID_SUOTA_MEM_INFO),
        ),
        HpyCharId.SUOTA_PATCH_LEN to CharLocation(
            UUID.fromString(UuidConstants.UUID_SUOTA_SERVICE),
            UUID.fromString(UuidConstants.UUID_SUOTA_PATCH_LEN),
        ),
        HpyCharId.SUOTA_PATCH_DATA to CharLocation(
            UUID.fromString(UuidConstants.UUID_SUOTA_SERVICE),
            UUID.fromString(UuidConstants.UUID_SUOTA_PATCH_DATA),
        ),
        HpyCharId.SUOTA_STATUS to CharLocation(
            UUID.fromString(UuidConstants.UUID_SUOTA_SERVICE),
            UUID.fromString(UuidConstants.UUID_SUOTA_STATUS),
        ),
        HpyCharId.SUOTA_L2CAP_PSM to CharLocation(
            UUID.fromString(UuidConstants.UUID_SUOTA_SERVICE),
            UUID.fromString(UuidConstants.UUID_SUOTA_L2CAP_PSM),
        ),
        HpyCharId.SUOTA_VERSION to CharLocation(
            UUID.fromString(UuidConstants.UUID_SUOTA_SERVICE),
            UUID.fromString(UuidConstants.UUID_SUOTA_VERSION),
        ),
        HpyCharId.SUOTA_MTU to CharLocation(
            UUID.fromString(UuidConstants.UUID_SUOTA_SERVICE),
            UUID.fromString(UuidConstants.UUID_SUOTA_MTU),
        ),
        HpyCharId.SUOTA_PATCH_DATA_CHAR_SIZE to CharLocation(
            UUID.fromString(UuidConstants.UUID_SUOTA_SERVICE),
            UUID.fromString(UuidConstants.UUID_SUOTA_PATCH_DATA_CHAR_SIZE),
        ),
    )

    private val uuidToCharId: Map<UUID, HpyCharId> =
        charIdToUuid.entries.associate { (charId, loc) -> loc.charUuid to charId }

    fun getLocation(charId: HpyCharId): CharLocation? = charIdToUuid[charId]

    fun getCharId(charUuid: UUID): HpyCharId? = uuidToCharId[charUuid]

    val UUID_CCC: UUID = UUID.fromString(UuidConstants.UUID_CCC)

    val UUID_HPY_HCS: UUID = UUID.fromString(UuidConstants.UUID_HPY_HCS)
    val UUID_SERVICE_DEVICE_INFO: UUID = UUID.fromString(UuidConstants.UUID_SERVICE_DEVICE_INFO)
    val UUID_SUOTA_SERVICE: UUID = UUID.fromString(UuidConstants.UUID_SUOTA_SERVICE)

    fun discoverAvailableChars(
        services: List<android.bluetooth.BluetoothGattService>,
    ): Set<HpyCharId> {
        val available = mutableSetOf<HpyCharId>()
        for (service in services) {
            for (char in service.characteristics) {
                val charId = getCharId(char.uuid)
                if (charId != null) available.add(charId)
            }
        }
        return available
    }
}
