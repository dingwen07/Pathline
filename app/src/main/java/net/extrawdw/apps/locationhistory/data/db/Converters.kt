package net.extrawdw.apps.locationhistory.data.db

import androidx.room.TypeConverter
import net.extrawdw.apps.locationhistory.core.AnnotationKind
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.core.CandidateCoordinateFrame
import net.extrawdw.apps.locationhistory.core.CandidateOrigin
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.NetworkTransport
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateRepairDecision
import net.extrawdw.apps.locationhistory.core.TransportMode

/** Stores enums by stable name so reordering ordinals never corrupts persisted data. */
class Converters {
    @TypeConverter
    fun stateToString(v: DevicePhysicalState): String = v.name

    @TypeConverter
    fun stringToState(v: String): DevicePhysicalState =
        runCatching { DevicePhysicalState.valueOf(v) }.getOrDefault(DevicePhysicalState.UNKNOWN)

    @TypeConverter
    fun modeToString(v: TransportMode): String = v.name

    @TypeConverter
    fun stringToMode(v: String): TransportMode =
        runCatching { TransportMode.valueOf(v) }.getOrDefault(TransportMode.UNKNOWN)

    @TypeConverter
    fun sourceToString(v: PlaceSource): String = v.name

    @TypeConverter
    fun stringToSource(v: String): PlaceSource =
        runCatching { PlaceSource.valueOf(v) }.getOrDefault(PlaceSource.INFERRED)

    @TypeConverter
    fun placeCoordinateStateToString(v: PlaceCoordinateState): String = v.name

    @TypeConverter
    fun stringToPlaceCoordinateState(v: String): PlaceCoordinateState =
        runCatching { PlaceCoordinateState.valueOf(v) }.getOrDefault(PlaceCoordinateState.UNKNOWN)

    @TypeConverter
    fun repairDecisionToString(v: PlaceCoordinateRepairDecision): String = v.name

    @TypeConverter
    fun stringToRepairDecision(v: String): PlaceCoordinateRepairDecision =
        runCatching { PlaceCoordinateRepairDecision.valueOf(v) }
            .getOrDefault(PlaceCoordinateRepairDecision.UNKNOWN)

    @TypeConverter
    fun candidateFrameToString(v: CandidateCoordinateFrame): String = v.name

    @TypeConverter
    fun stringToCandidateFrame(v: String): CandidateCoordinateFrame =
        runCatching { CandidateCoordinateFrame.valueOf(v) }
            .getOrDefault(CandidateCoordinateFrame.UNKNOWN)

    @TypeConverter
    fun candidateOriginToString(v: CandidateOrigin): String = v.name

    @TypeConverter
    fun stringToCandidateOrigin(v: String): CandidateOrigin =
        runCatching { CandidateOrigin.valueOf(v) }.getOrDefault(CandidateOrigin.UNKNOWN)

    @TypeConverter
    fun transportToString(v: NetworkTransport?): String? = v?.name

    @TypeConverter
    fun stringToTransport(v: String?): NetworkTransport? =
        v?.let { runCatching { NetworkTransport.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun annotationTargetToString(v: AnnotationTarget): String = v.name

    @TypeConverter
    fun stringToAnnotationTarget(v: String): AnnotationTarget = AnnotationTarget.valueOf(v)

    @TypeConverter
    fun annotationKindToString(v: AnnotationKind): String = v.name

    @TypeConverter
    fun stringToAnnotationKind(v: String): AnnotationKind = AnnotationKind.valueOf(v)
}
