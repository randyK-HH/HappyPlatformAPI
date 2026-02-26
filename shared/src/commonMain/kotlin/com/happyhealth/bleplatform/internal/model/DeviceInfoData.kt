package com.happyhealth.bleplatform.internal.model

data class DeviceInfoData(
    val serialNumber: String = "",
    val manufacturerName: String = "",
    val fwVersion: String = "",
    val swVersion: String = "",
    val modelNumber: String = "",
) {
    val firmwareTier: FirmwareTier get() = FirmwareTier.fromVersionString(fwVersion)
    val supportsL2capDownload: Boolean get() = FirmwareTier.supportsL2capDownload(fwVersion)
}
