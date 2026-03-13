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
         * Check if the notification sender byte is meaningful for the given firmware version.
         * Requires FW >= 2.5.0.59 (or FDA equivalents >= 2.5.1.0).
         */
        fun supportsNotifSender(version: String): Boolean {
            val parts = version.trim().split(".")
            if (parts.size < 4) return false

            val project = parts[0].toIntOrNull() ?: return false
            val major = parts[1].toIntOrNull() ?: return false
            val minor = parts[2].toIntOrNull() ?: return false
            val build = parts[3].substringBefore('-').toIntOrNull() ?: return false

            if (project != 2 || major != 5) return project > 2

            // 2.5.1.0+ are FDA releases (equivalent to >= 2.5.0.50)
            if (minor >= 1) return true

            // 2.5.0.BUILD: need build >= 59
            return minor == 0 && build >= 59
        }

        /**
         * Check if this firmware version requires GATT-based FW updates because
         * its SUOTA L2CAP implementation is broken.
         *
         * Affected versions:
         * - All 2.4.x.x (Tier 1 — no L2CAP SUOTA support)
         * - 2.5.0.BUILD where BUILD < 52
         * - 2.5.1.0 (FDA release with broken L2CAP SUOTA)
         *
         * Unaffected: 2.5.0.52+, 2.5.1.1+, 2.5.2+, 3.x+, bootloader, mfg
         */
        fun requiresGattFwUpdate(version: String): Boolean {
            val parts = version.trim().split(".")
            if (parts.size < 4) return false

            val project = parts[0].toIntOrNull() ?: return false
            val major = parts[1].toIntOrNull() ?: return false
            val minor = parts[2].toIntOrNull() ?: return false
            val build = parts[3].substringBefore('-').toIntOrNull() ?: return false

            // Only application FW (project 2) is relevant
            if (project != 2) return false

            // 2.4.x.x — Tier 1, no L2CAP SUOTA
            if (major == 4) return true

            // Below 2.5 is not application FW we'd update
            if (major < 5) return false

            // 2.5.0.BUILD: broken below build 52
            if (minor == 0) return build < 52

            // 2.5.1.0 exactly: FDA release with broken L2CAP SUOTA
            if (minor == 1 && build == 0) return true

            // 2.5.1.1+, 2.5.2+, etc. — fixed
            return false
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
            // Build part may have a git-describe suffix (e.g. "78-0-g3a8a025b")
            val build = parts[3].substringBefore('-').toIntOrNull() ?: return false

            if (project != 2 || major != 5) return project > 2

            // 2.5.1.0+ are FDA releases (equivalent to >= 2.5.0.50)
            if (minor >= 1) return true

            // 2.5.0.BUILD: need build >= 54
            return minor == 0 && build >= 54
        }
    }
}
