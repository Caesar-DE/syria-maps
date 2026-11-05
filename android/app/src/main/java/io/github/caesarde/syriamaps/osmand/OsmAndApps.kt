package io.github.caesarde.syriamaps.osmand

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/** An installed OsmAnd variant. */
data class InstalledOsmAnd(val packageName: String, val label: String, val versionName: String)

/**
 * Detection of the official OsmAnd apps. Package names and intent actions are taken from the
 * OsmAnd source (AndroidManifest.xml, AidlPermissionRequestActivity); see docs/android-companion.md.
 */
object OsmAndApps {
    /** Order = preference when several are installed. */
    val PACKAGES = listOf(
        "net.osmand.plus" to "OsmAnd+",
        "net.osmand" to "OsmAnd",
        "net.osmand.dev" to "OsmAnd (nightly)",
    )

    /** AIDL V2 service action (OsmAnd manifest: net.osmand.aidl.OsmandAidlServiceV2). */
    const val AIDL_SERVICE_ACTION = "net.osmand.aidl.OsmandAidlServiceV2"

    /** Permission request screen added in recent OsmAnd versions (AidlPermissionRequestActivity). */
    const val ACTION_REQUEST_PERMISSIONS = "net.osmand.aidl.REQUEST_PERMISSIONS"
    const val EXTRA_GROUPS = "groups"
    const val EXTRA_GRANTED_GROUPS = "granted_groups"

    /** Permission group covering addMapLayer/updateMapLayer/removeMapLayer/setMapLocation. */
    const val GROUP_MAP = "map"

    const val FREE_PACKAGE = "net.osmand"

    fun installed(context: Context): List<InstalledOsmAnd> {
        val pm = context.packageManager
        return PACKAGES.mapNotNull { (pkg, label) ->
            try {
                val info = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
                }
                InstalledOsmAnd(pkg, label, info.versionName ?: "?")
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    fun permissionRequestIntent(pkg: String): Intent =
        Intent(ACTION_REQUEST_PERMISSIONS).setPackage(pkg).putExtra(EXTRA_GROUPS, arrayOf(GROUP_MAP))

    /** True if the installed OsmAnd offers the permission request screen (newer versions only). */
    fun supportsPermissionRequest(context: Context, pkg: String): Boolean =
        context.packageManager.queryIntentActivities(permissionRequestIntent(pkg), 0).isNotEmpty()

    fun launchIntent(context: Context, pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)

    fun storeIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$FREE_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun storeWebIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$FREE_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
