package io.github.caesarde.syriamaps.osmand

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded .osf package to OsmAnd with a normal ACTION_VIEW intent. OsmAnd's
 * MapActivity declares VIEW filters for content URIs ending in ".osf", and shows its own import
 * screen, so the user confirms the import. We deliberately do NOT use the AIDL importProfile
 * method, which has a "silent" flag and needs the "settings" permission group.
 */
object OsfInstaller {
    fun packageFile(context: Context): File = File(File(context.cacheDir, "osf"), "syria-maps.osf")

    fun importIntent(context: Context, file: File, osmandPackage: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/octet-stream")
            .setPackage(osmandPackage)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
