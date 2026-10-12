package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H10HevcPolicyTest {
    private val firmware = "HiSTBAndroidV6/Hi3798CV200/Hi3798CV200:7.0/HMD-2.1.6/2020-07-09.104343:eng/release-keys"

    @Test fun keepsPreviouslyAcceptedModel() {
        assertTrue(H10HevcPolicy.accepts("H10-G4", "bigfish", 24, H10HevcPolicy.DECODER))
    }

    @Test fun acceptsCapturedColdBootIdentity() {
        assertTrue(H10HevcPolicy.accepts("Hi3798CV200", "bigfish", 24, H10HevcPolicy.DECODER, firmware))
    }

    @Test fun acceptsCapturedAndroidFrameworkModelWithFirmwareGuard() {
        assertTrue(H10HevcPolicy.accepts("H10四代", "bigfish", 24, H10HevcPolicy.DECODER, firmware))
        assertFalse(H10HevcPolicy.accepts("H10四代", "bigfish", 24, H10HevcPolicy.DECODER))
        assertFalse(H10HevcPolicy.accepts("H10二代", "bigfish", 24, H10HevcPolicy.DECODER, firmware))
    }

    @Test fun chipNameAloneDoesNotEnableAdaptation() {
        assertFalse(H10HevcPolicy.accepts("Hi3798CV200", "bigfish", 24, H10HevcPolicy.DECODER))
        assertFalse(H10HevcPolicy.accepts("Hi3798CV200", "bigfish", 24, H10HevcPolicy.DECODER, firmware.replace("HMD-2.1.6", "OtherROM")))
    }

    @Test fun hardwareSdkAndDecoderGuardsStillApply() {
        assertFalse(H10HevcPolicy.accepts("Hi3798CV200", "other", 24, H10HevcPolicy.DECODER, firmware))
        assertFalse(H10HevcPolicy.accepts("Hi3798CV200", "bigfish", 25, H10HevcPolicy.DECODER, firmware))
        assertFalse(H10HevcPolicy.accepts("Hi3798CV200", "bigfish", 24, "OMX.google.hevc.decoder", firmware))
        assertFalse(H10HevcPolicy.accepts("OtherBox", "bigfish", 24, H10HevcPolicy.DECODER, firmware))
    }
}
