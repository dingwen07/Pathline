package net.extrawdw.apps.locationhistory.core.coordinates

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainlandChinaRegionGeometryTest {
    private val asset by lazy {
        val candidates = listOf(
            File("src/main/res/raw/mainland_china_adm1.json"),
            File("app/src/main/res/raw/mainland_china_adm1.json"),
        )
        candidates.firstOrNull(File::isFile)
            ?: error("mainland boundary asset not found")
    }
    private val geometry by lazy { MainlandChinaRegion.RegionGeometry.parse(asset.readText()) }

    @Test
    fun generatedAssetHash_isPinned() {
        val hash = MessageDigest.getInstance("SHA-256").digest(asset.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "94a70747aa17f1e29e981fdbcd62044a6ea156a79f65a272717f575ab16d6fd0",
            hash,
        )
    }

    @Test
    fun includesRepresentativeMainlandCitiesAndHainan() {
        assertTrue(geometry.contains(39.9042, 116.4074)) // Beijing
        assertTrue(geometry.contains(31.2304, 121.4737)) // Shanghai
        assertTrue(geometry.contains(18.2528, 109.5119)) // Sanya, Hainan
        assertTrue(geometry.contains(43.8256, 87.6168)) // Urumqi
    }

    @Test
    fun excludesSpecialRegionsTaiwanAndNeighbors() {
        assertFalse(geometry.contains(22.3193, 114.1694)) // central Hong Kong
        assertFalse(geometry.contains(22.1987, 113.5439)) // Macao
        assertFalse(geometry.contains(25.0330, 121.5654)) // Taipei
        assertFalse(geometry.contains(1.3521, 103.8198)) // Singapore
        assertFalse(geometry.contains(21.0278, 105.8342)) // Hanoi
        assertFalse(geometry.contains(47.8864, 106.9057)) // Ulaanbaatar
    }
}
