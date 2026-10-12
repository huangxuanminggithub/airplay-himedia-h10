package io.github.jqssun.airplay.renderer

/** Opt-in for the H10 identities observed before and after a cold boot. */
object H10HevcPolicy {
    const val DECODER = "OMX.hisi.video.decoder.hevc"
    private const val BOOT_FINGERPRINT =
        "HiSTBAndroidV6/Hi3798CV200/Hi3798CV200:7.0/HMD-2.1.6/2020-07-09.104343:eng/release-keys"

    @JvmStatic
    fun accepts(model: String, hardware: String, sdk: Int, decoder: String,
                fingerprint: String = ""): Boolean {
        val normalizedModel = model.filter { it.isLetterOrDigit() }
        // This firmware reports the chip name after boot, even though the earlier
        // session reported H10-G4. Require the exact inspected firmware for that
        // alias; other HiSilicon models/firmwares retain the upstream policy.
        val knownModel = normalizedModel.equals("H10G4", ignoreCase = true) ||
            ((normalizedModel.equals("Hi3798CV200", ignoreCase = true) ||
                normalizedModel == "H10四代") && fingerprint == BOOT_FINGERPRINT)
        return knownModel && hardware.equals("bigfish", ignoreCase = true) &&
            sdk == 24 && decoder.equals(DECODER, ignoreCase = true)
    }
}
