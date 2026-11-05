package io.github.caesarde.syriamaps.osmand

import io.github.caesarde.syriamaps.data.LayerInfo
import io.github.caesarde.syriamaps.data.PointFeature
import net.osmand.aidlapi.map.ALatLon
import net.osmand.aidlapi.map.SetMapLocationParams
import net.osmand.aidlapi.maplayer.AMapLayer
import net.osmand.aidlapi.maplayer.AddMapLayerParams
import net.osmand.aidlapi.maplayer.RemoveMapLayerParams
import net.osmand.aidlapi.maplayer.UpdateMapLayerParams
import net.osmand.aidlapi.maplayer.point.AMapPoint

sealed interface SyncOutcome {
    data class Done(val points: Int) : SyncOutcome

    /** OsmAnd answered `false`: usually the app is not allowed (Connected apps / "map" permission). */
    data class Rejected(val method: String) : SyncOutcome
    data class Failed(val result: AidlResult<*>) : SyncOutcome
}

/**
 * Pushes a point layer into OsmAnd with the documented AIDL methods
 * addMapLayer / updateMapLayer / removeMapLayer (IOsmAndAidlInterface, OsmAnd-api).
 *
 * OsmAnd keeps these layers in memory only and updateMapLayer merges points, so a sync
 * rebuilds the layer: remove → add (empty) → update in chunks. Must be repeated after OsmAnd restarts.
 */
class LayerSync(private val connection: OsmAndConnection) {

    suspend fun sync(layer: LayerInfo, features: List<PointFeature>, onProgress: (Int, Int) -> Unit = { _, _ -> }): SyncOutcome {
        val layerId = PointMapping.osmandLayerId(layer)
        val name = layer.titleAr
        val specs = features.map { PointMapping.toSpec(layer, it) }

        // Removing a layer that does not exist returns false; that is fine.
        when (val r = connection.call("removeMapLayer") { it.removeMapLayer(RemoveMapLayerParams(layerId)) }) {
            is AidlResult.Ok -> Unit
            else -> return SyncOutcome.Failed(r)
        }
        when (val r = connection.call("addMapLayer") {
            it.addMapLayer(AddMapLayerParams(AMapLayer(layerId, name, Z_ORDER, emptyList())))
        }) {
            is AidlResult.Ok -> if (!r.value) return SyncOutcome.Rejected("addMapLayer")
            else -> return SyncOutcome.Failed(r)
        }
        var sent = 0
        for (chunk in PointMapping.chunks(specs)) {
            val points = chunk.map { it.toAMapPoint(layerId) }
            when (val r = connection.call("updateMapLayer") {
                it.updateMapLayer(UpdateMapLayerParams(AMapLayer(layerId, name, Z_ORDER, points)))
            }) {
                is AidlResult.Ok -> if (!r.value) return SyncOutcome.Rejected("updateMapLayer")
                else -> return SyncOutcome.Failed(r)
            }
            sent += chunk.size
            onProgress(sent, specs.size)
        }
        return SyncOutcome.Done(sent)
    }

    suspend fun remove(layer: LayerInfo): AidlResult<Boolean> =
        connection.call("removeMapLayer") { it.removeMapLayer(RemoveMapLayerParams(PointMapping.osmandLayerId(layer))) }

    /** Moves OsmAnd's map (setMapLocation, "map" permission group). Zoom is an int in the API. */
    suspend fun moveMap(latitude: Double, longitude: Double, zoom: Int): AidlResult<Boolean> =
        connection.call("setMapLocation") {
            it.setMapLocation(SetMapLocationParams(latitude, longitude, zoom, 0f, true))
        }

    private fun PointSpec.toAMapPoint(layerId: String) = AMapPoint(
        id, shortName, fullName, typeName, layerId, color, ALatLon(latitude, longitude), details, null,
    )

    companion object {
        /** OsmAnd's documented default z-order for user layers. */
        const val Z_ORDER = 5.5f
    }
}
