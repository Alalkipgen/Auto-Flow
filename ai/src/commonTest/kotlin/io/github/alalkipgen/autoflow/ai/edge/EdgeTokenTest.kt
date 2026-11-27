package io.github.alalkipgen.autoflow.ai.edge

import kotlin.test.Test
import kotlin.test.assertEquals

/** Values from the reference edge-tts 7.3.1 `generate_sec_ms_gec_v2` (Q-45 → (a), D-90). */
class EdgeTokenTest {
    private val token = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    @Test
    fun matchesTheReferenceImplementation() {
        assertEquals("52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6", EdgeToken.secMsGec(0, token))
        assertEquals("2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2", EdgeToken.secMsGec(1_700_000_000, token))
        assertEquals("E34A083DE9C21528-206EE26E9122C0E7BCAC71A0308AF76C228639BE8FC3CF947FB2F5A7D6D73292", EdgeToken.secMsGec(1_798_000_123, token))
    }

    @Test
    fun staysTheSameInsideOneFiveMinuteWindow() {
        assertEquals(EdgeToken.secMsGec(1_795_190_400, token), EdgeToken.secMsGec(1_795_190_699, token))
        assertEquals("D9AB07F97F9413B2-5DD68C6788FE79B2DC3F907837B5FCDCB16FF9C10B8AEE94857FA0B48334FEAE", EdgeToken.secMsGec(1_795_190_400, token))
    }
}
