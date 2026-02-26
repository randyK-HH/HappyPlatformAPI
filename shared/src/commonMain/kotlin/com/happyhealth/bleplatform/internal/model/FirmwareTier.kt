package com.happyhealth.bleplatform.internal.model

enum class FirmwareTier {
    TIER_0,
    TIER_1,
    TIER_2;

    companion object {
        /**
         * Parse a firmware version string (PROJECT.MAJOR.MINOR.BUILD) and
         * determine the firmware tier.
         *
         * Tier 0: Bootloader (0.0.0.0) or Manufacturing FW (1.5.x.x)
         * Tier 1: Legacy Application FW (2.4.x.x)
         * Tier 2: Current Application FW (2.5.x.x)
         */
        fun fromVersionString(version: String): FirmwareTier {
            val parts = version.trim().split(".")
            if (parts.size < 2) return TIER_0

            val project = parts[0].toIntOrNull() ?: return TIER_0
            val major = parts[1].toIntOrNull() ?: return TIER_0

            return when {
                project == 0 -> TIER_0                   // Bootloader
                project == 1 && major == 5 -> TIER_0     // Manufacturing FW
                project == 2 && major == 4 -> TIER_1     // Legacy Application FW
                project == 2 && major >= 5 -> TIER_2     // Current Application FW
                project >= 3 -> TIER_2                   // Future FW (assume Tier 2)
                else -> TIER_0
            }
        }

        /**
         * Check if L2CAP download is supported for the given firmware version.
         * Requires FW >= 2.5.0.54 (or FDA equivalents >= 2.5.1.0).
         */
        fun supportsL2capDownload(version: String): Boolean {
            val parts = version.trim().split(".")
            if (parts.size < 4) return false

            val project = parts[0].toIntOrNull() ?: return false
            val major = parts[1].toIntOrNull() ?: return false
            val minor = parts[2].toIntOrNull() ?: return false
            val build = parts[3].toIntOrNull() ?: return false

            if (project != 2 || major != 5) return project > 2

            // 2.5.1.0+ are FDA releases (equivalent to >= 2.5.0.50)
            if (minor >= 1) return true

            // 2.5.0.BUILD: need build >= 54
            return minor == 0 && build >= 54
        }
    }
}
