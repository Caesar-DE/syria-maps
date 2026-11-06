package io.github.caesarde.syriamaps.osmand

import android.view.KeyEvent
import java.util.concurrent.ConcurrentHashMap
import net.osmand.aidlapi.IOsmAndAidlCallback
import net.osmand.aidlapi.contextmenu.AContextMenuButton
import net.osmand.aidlapi.contextmenu.ContextMenuButtonsParams
import net.osmand.aidlapi.contextmenu.RemoveContextMenuButtonsParams
import net.osmand.aidlapi.gpx.AGpxBitmap
import net.osmand.aidlapi.logcat.OnLogcatMessageParams
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams
import net.osmand.aidlapi.search.SearchResult

/**
 * Adds a "Report a problem" button to OsmAnd's context menu of our own map points
 * (addContextMenuButtons, IOsmAndAidlInterface). OsmAnd shows it for points whose layer id matches
 * (AMapPointMenuController) and calls back onContextMenuButtonClicked(buttonId, pointId, layerId).
 * Like the layers themselves, buttons live in OsmAnd's memory and are re-added after every sync.
 */
class ReportButton(
    private val connection: OsmAndConnection,
    private val appPackage: String,
    private val onClick: (layerId: String, pointId: String) -> Unit,
) {
    private val callback = object : IOsmAndAidlCallback.Stub() {
        override fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {
            if (buttonId == BUTTON_ID && pointId != null && layerId != null) onClick(layerId, pointId)
        }

        override fun onSearchComplete(resultSet: MutableList<SearchResult>?) = Unit
        override fun onUpdate() = Unit
        override fun onAppInitialized() = Unit
        override fun onGpxBitmapCreated(bitmap: AGpxBitmap?) = Unit
        override fun updateNavigationInfo(directionInfo: ADirectionInfo?) = Unit
        override fun onVoiceRouterNotify(params: OnVoiceNavigationParams?) = Unit
        override fun onKeyEvent(params: KeyEvent?) = Unit
        override fun onLogcatMessage(params: OnLogcatMessageParams?) = Unit
    }

    /** [osmandLayerId] as used for the layer; [caption] in the UI language. */
    suspend fun add(osmandLayerId: String, caption: String): AidlResult<Long> {
        val button = AContextMenuButton(BUTTON_ID, caption, "", null, null, true, true)
        // A non-matching placeholder id keeps the button off other apps' points: OsmAnd shows it when the
        // id list is empty OR contains the point OR the layer id matches, so the list must not be empty.
        val params = ContextMenuButtonsParams(
            button, null, buttonsId(osmandLayerId), appPackage, osmandLayerId, 0L, listOf(NO_POINT),
        )
        val result = connection.call("addContextMenuButtons") { it.addContextMenuButtons(params, callback) }
        if (result is AidlResult.Ok) callbackIds[osmandLayerId] = result.value
        return result
    }

    private val callbackIds = ConcurrentHashMap<String, Long>()

    /** Removes the buttons from every layer they were added to. */
    suspend fun removeAll() {
        for ((layerId, cbId) in callbackIds.toMap()) {
            connection.call("removeContextMenuButtons") {
                it.removeContextMenuButtons(RemoveContextMenuButtonsParams(buttonsId(layerId), cbId))
            }
            callbackIds.remove(layerId)
        }
    }

    companion object {
        const val BUTTON_ID = 1
        private const val NO_POINT = "syria-maps:none"
        fun buttonsId(osmandLayerId: String) = "$osmandLayerId/report"
    }
}
