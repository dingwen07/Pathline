package net.extrawdw.apps.locationhistory.data.db

import androidx.room3.ColumnTypeConverter
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
    @ColumnTypeConverter
    fun stateToString(v: DevicePhysicalState): String = v.name

    @ColumnTypeConverter
    fun stringToState(v: String): DevicePhysicalState =
        runCatching { DevicePhysicalState.valueOf(v) }.getOrDefault(DevicePhysicalState.UNKNOWN)

    @ColumnTypeConverter
    fun modeToString(v: TransportMode): String = v.name

    @ColumnTypeConverter
    fun stringToMode(v: String): TransportMode =
        runCatching { TransportMode.valueOf(v) }.getOrDefault(TransportMode.UNKNOWN)

    @ColumnTypeConverter
    fun sourceToString(v: PlaceSource): String = v.name

    @ColumnTypeConverter
    fun stringToSource(v: String): PlaceSource =
        runCatching { PlaceSource.valueOf(v) }.getOrDefault(PlaceSource.INFERRED)

    @ColumnTypeConverter
    fun placeCoordinateStateToString(v: PlaceCoordinateState): String = v.name

    @ColumnTypeConverter
    fun stringToPlaceCoordinateState(v: String): PlaceCoordinateState =
        runCatching { PlaceCoordinateState.valueOf(v) }.getOrDefault(PlaceCoordinateState.UNKNOWN)

    @ColumnTypeConverter
    fun repairDecisionToString(v: PlaceCoordinateRepairDecision): String = v.name

    @ColumnTypeConverter
    fun stringToRepairDecision(v: String): PlaceCoordinateRepairDecision =
        runCatching { PlaceCoordinateRepairDecision.valueOf(v) }
            .getOrDefault(PlaceCoordinateRepairDecision.UNKNOWN)

    @ColumnTypeConverter
    fun candidateFrameToString(v: CandidateCoordinateFrame): String = v.name

    @ColumnTypeConverter
    fun stringToCandidateFrame(v: String): CandidateCoordinateFrame =
        runCatching { CandidateCoordinateFrame.valueOf(v) }
            .getOrDefault(CandidateCoordinateFrame.UNKNOWN)

    @ColumnTypeConverter
    fun candidateOriginToString(v: CandidateOrigin): String = v.name

    @ColumnTypeConverter
    fun stringToCandidateOrigin(v: String): CandidateOrigin =
        runCatching { CandidateOrigin.valueOf(v) }.getOrDefault(CandidateOrigin.UNKNOWN)

    @ColumnTypeConverter
    fun transportToString(v: NetworkTransport?): String? = v?.name

    @ColumnTypeConverter
    fun stringToTransport(v: String?): NetworkTransport? =
        v?.let { runCatching { NetworkTransport.valueOf(it) }.getOrNull() }

    @ColumnTypeConverter
    fun annotationTargetToString(v: AnnotationTarget): String = v.name

    @ColumnTypeConverter
    fun stringToAnnotationTarget(v: String): AnnotationTarget = AnnotationTarget.valueOf(v)

    @ColumnTypeConverter
    fun annotationKindToString(v: AnnotationKind): String = v.name

    @ColumnTypeConverter
    fun stringToAnnotationKind(v: String): AnnotationKind = AnnotationKind.valueOf(v)
}
