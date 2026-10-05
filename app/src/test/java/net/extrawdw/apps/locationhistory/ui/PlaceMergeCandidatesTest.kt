package net.extrawdw.apps.locationhistory.ui

import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.repo.placeMergePairKey
import org.junit.Assert.*
import org.junit.Test

class PlaceMergeCandidatesTest {
    @Test
    fun ignoredPairIsSymmetricAndDoesNotAffectOtherPairsOrManualPicker() {
        val a = place(1, 0.0)
        val b = place(2, 20.0)
        val c = place(3, 40.0)
        val ignored = setOf(placeMergePairKey(b, a))
        assertEquals(placeMergePairKey(a, b), placeMergePairKey(b, a))
        val pairs = findPlaceMergeCandidates(listOf(c, b, a), ignored)
        assertEquals(setOf(1L to 3L, 2L to 3L), pairs.map { it.first.id to it.second.id }.toSet())
        assertTrue(rankPlaceMergeTargets(a, listOf(a, b, c), "").contains(b))
        assertEquals(3, findPlaceMergeCandidates(listOf(a, b.copy(createdAtMs = 99), c), ignored).size)
    }
    @Test
    fun candidatesUseCentersNotOverlappingRadiiAndIncludeOnlyWithin100Meters() {
        val first = place(1, 0.0)
        val inside = place(2, 99.9)
        val outside = place(3, 100.1).copy(radiusMeters = 500.0)
        val pairs = findPlaceMergeCandidates(listOf(outside, first, inside))
        assertTrue(pairs.any { it.first.id == 1L && it.second.id == 2L })
        assertFalse(pairs.any { it.first.id == 1L && it.second.id == 3L })
        assertTrue(pairs.all { it.distanceMeters <= 100.0 })
    }

    @Test
    fun sameNamesAreGroupedWithoutDuplicatingPairsAndEachGroupIsNearestFirst() {
        val places = listOf(place(1, 0.0, "Cafe"), place(2, 90.0, " cafe "),
            place(3, 20.0, "Cafe"), place(4, 50.0, "Store"))
        val pairs = findPlaceMergeCandidates(places)
        assertEquals(6, pairs.size)
        assertEquals(6, pairs.map { it.first.id to it.second.id }.toSet().size)
        assertEquals(3, pairs.count { it.sameName })
        for (sameName in listOf(true, false)) {
            val distances = pairs.filter { it.sameName == sameName }.map { it.distanceMeters }
            assertEquals(distances.sorted(), distances)
        }
    }

    @Test
    fun pairsAcrossDateLineAreFound() {
        val west = place(1, 0.0).copy(longitude = 179.9999)
        val east = place(2, 0.0).copy(longitude = -179.9999)
        assertEquals(1, findPlaceMergeCandidates(listOf(west, east)).size)
    }

    @Test
    fun legacyAndInvalidCentersNeverProduceDistanceCandidates() {
        val places = listOf(place(1, 0.0),
            place(2, 0.0).copy(coordinateState = PlaceCoordinateState.UNKNOWN),
            place(3, 0.0).copy(latitude = Double.NaN),
            place(4, 0.0).copy(longitude = 181.0))
        assertTrue(findPlaceMergeCandidates(places).isEmpty())
    }

    @Test
    fun pickerExcludesSourceAndRanksSearchMatchesRelativeToItWithoutDistanceLimit() {
        val source = place(1, 0.0)
        val near = place(2, 20.0, "Coffee")
        val far = place(3, 2000.0, "Other").copy(address = "Coffee Street")
        val unknown = place(4, 0.0, "Coffee").copy(coordinateState = PlaceCoordinateState.UNKNOWN)
        val unrelated = place(5, 10.0, "Library")
        assertEquals(listOf(near, far, unknown),
            rankPlaceMergeTargets(source, listOf(far, source, unknown, near, unrelated), " cOffEe "))
    }

    @Test
    fun emptyAndSinglePlaceHaveNoPairs() {
        assertTrue(findPlaceMergeCandidates(emptyList()).isEmpty())
        assertTrue(findPlaceMergeCandidates(listOf(place(1, 0.0))).isEmpty())
        assertTrue(rankPlaceMergeTargets(place(1, 0.0), listOf(place(1, 0.0)), "").isEmpty())
    }

    private fun place(id: Long, metersNorth: Double, name: String = "Place $id") = PlaceEntity(
        id = id, name = name, latitude = Math.toDegrees(metersNorth / Geo.EARTH_RADIUS_METERS),
        longitude = 0.0, radiusMeters = 30.0, category = null, source = PlaceSource.USER,
        googlePlaceId = null, address = null, confirmed = true, createdAtMs = 0,
        coordinateState = PlaceCoordinateState.WGS84_CANONICAL,
    )
}
