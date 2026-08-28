package attendance.help.device.utils

import android.os.Build

object DeviceHints {
    /** True on LDPlayer, Android Emulator, Genymotion, etc. */
    fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val product = Build.PRODUCT.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        return fingerprint.contains("generic") ||
            fingerprint.contains("unknown") ||
            model.contains("emulator") ||
            model.contains("ldplayer") ||
            model.contains("android sdk built for") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            hardware.contains("vbox") ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            manufacturer.contains("genymotion")
    }
}
