package io.github.caesarde.syriamaps.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.caesarde.syriamaps.BuildConfig
import io.github.caesarde.syriamaps.MainViewModel
import io.github.caesarde.syriamaps.R
import io.github.caesarde.syriamaps.ReportStatus
import io.github.caesarde.syriamaps.UiState
import io.github.caesarde.syriamaps.data.CommunitySubmission
import io.github.caesarde.syriamaps.data.NoteText
import io.github.caesarde.syriamaps.data.OsmEdit
import io.github.caesarde.syriamaps.data.ReportDestination
import io.github.caesarde.syriamaps.data.ReportDraft
import io.github.caesarde.syriamaps.data.ReportLocation
import io.github.caesarde.syriamaps.data.ReportType
import java.util.Locale

/**
 * Report a map problem or a missing place as a public OpenStreetMap note.
 * [onUseLocation] asks for the location permission first (handled by the activity).
 */
@Composable
fun ReportScreen(state: UiState, vm: MainViewModel, onUseLocation: () -> Unit) {
    val c = LocalInk.current
    val draft = state.report ?: return
    val status = state.reportStatus

    Box(Modifier.fillMaxSize().background(c.ground)) {
        ScribbleBackground(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.report_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.text,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(44.dp)
                        .inkSurface(c.paper, c.ink, shadow = c.shadow, radius = 22.dp, offset = 3.dp)
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = vm::closeReport),
                    contentAlignment = Alignment.Center,
                ) { InkIcon(InkGlyph.Close, size = 22.dp, ink = c.ink) }
            }
            if (BuildConfig.DEBUG && draft.destination != ReportDestination.COMMUNITY) {
                Text(
                    stringResource(R.string.report_test_server),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Ink,
                    modifier = Modifier.padding(top = 6.dp).background(Palette.Yellow, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(12.dp))

            AnimatedContent(
                targetState = status is ReportStatus.Sent,
                modifier = Modifier.weight(1f),
                transitionSpec = { (fadeIn(tween(400)) + scaleIn(initialScale = 0.9f)) togetherWith fadeOut(tween(200)) },
                label = "sent",
            ) { sent ->
                if (sent && status is ReportStatus.Sent) {
                    SentView(status, draft.destination == ReportDestination.OSM_EDIT, vm::closeReport)
                } else {
                    EditView(state, draft, status, vm, onUseLocation)
                }
            }
        }
    }
}

@Composable
private fun EditView(state: UiState, draft: ReportDraft, status: ReportStatus, vm: MainViewModel, onUseLocation: () -> Unit) {
    val c = LocalInk.current
    var understood by rememberSaveable(draft.destination) { mutableStateOf(false) }
    val community = draft.destination == ReportDestination.COMMUNITY
    val direct = draft.destination == ReportDestination.OSM_EDIT
    val edit = if (direct && state.osm.user != null) vm.pendingOsmEdit() else null
    val complete = when {
        direct -> edit != null
        community -> CommunitySubmission.build(draft, true) != null
        else -> NoteText.build(draft) != null
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Appear(0) {
                DestinationPicker(draft.destination, showDirect = state.osm.available) { vm.updateReport(draft.copy(destination = it)) }
            }
            Appear(0) { TypePicker(draft.type) { vm.updateReport(draft.copy(type = it)) } }
            Appear(1) { LocationCard(draft.location, status, onUseLocation) }
            Appear(2) {
                if (direct) OsmEditCard(state, draft, edit, vm) else FieldsCard(draft) { vm.updateReport(it) }
            }
            Appear(3) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .inkSurface(c.paper, c.ink, shadow = c.shadow)
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Checkbox) { understood = !understood }
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    InkIcon(InkGlyph.Shield, size = 28.dp, ink = c.ink, fill = if (c.dark) Palette.Graphite else Palette.Yellow)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(
                                when {
                                    direct -> R.string.osm_edit_notice
                                    community -> R.string.report_team_notice
                                    else -> R.string.report_public_notice
                                },
                            ),
                            color = c.text, style = MaterialTheme.typography.bodySmall,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CheckBox(understood)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResource(
                                    when {
                                        direct -> R.string.osm_edit_confirm
                                        community -> R.string.report_team_confirm
                                        else -> R.string.report_public_confirm
                                    },
                                ),
                                color = c.text, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(status is ReportStatus.Failed, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                ProblemLine(stringResource(R.string.report_failed))
            }
            AnimatedVisibility(status is ReportStatus.RateLimited, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                ProblemLine(stringResource(R.string.report_rate_limited))
            }
            AnimatedVisibility(status is ReportStatus.Conflict, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                ProblemLine(stringResource(R.string.osm_edit_conflict))
            }
            AnimatedVisibility(status is ReportStatus.LoginNeeded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                ProblemLine(stringResource(R.string.osm_login_expired))
            }
            Spacer(Modifier.height(4.dp))
        }
        val sending = status is ReportStatus.Sending
        val spin = remember { Animatable(0f) }
        LaunchedEffect(sending) {
            while (sending) spin.animateTo(spin.value + 360f, tween(900, easing = LinearEasing))
        }
        InkButton(
            stringResource(
                when {
                    sending -> R.string.report_sending
                    direct -> R.string.osm_edit_send
                    community -> R.string.report_send_team
                    else -> R.string.report_send
                },
            ),
            vm::submitReport,
            Modifier.fillMaxWidth().padding(top = 10.dp),
            glyph = if (sending) InkGlyph.Sync else InkGlyph.Flag,
            enabled = complete && understood && !sending,
            iconRotation = spin.value,
        )
    }
}

/** OpenStreetMap note (public, no account) or the Syria Maps team (moderated community layer). */
@Composable
private fun DestinationPicker(current: ReportDestination, showDirect: Boolean, onPick: (ReportDestination) -> Unit) {
    val c = LocalInk.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.report_dest_title), color = c.text, style = MaterialTheme.typography.labelLarge)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOfNotNull(
                Triple(ReportDestination.OSM_NOTE, R.string.report_dest_osm, R.string.report_dest_osm_sub),
                Triple(ReportDestination.COMMUNITY, R.string.report_dest_team, R.string.report_dest_team_sub),
                if (showDirect) Triple(ReportDestination.OSM_EDIT, R.string.osm_dest_edit, R.string.osm_dest_edit_sub) else null,
            ).forEach { (dest, title, subtitle) ->
                val selected = dest == current
                val bg by animateColorAsState(if (selected) Palette.Yellow else c.paper, tween(250), label = "dest")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .inkSurface(bg, if (selected) Palette.Ink else c.ink, shadow = if (selected) Palette.Ink else c.shadow,
                            radius = 16.dp, offset = if (selected) 4.dp else 2.dp)
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.RadioButton) { onPick(dest) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(stringResource(title), color = if (selected) Palette.Ink else c.text, fontWeight = FontWeight.Bold)
                    Text(stringResource(subtitle), color = if (selected) Palette.Brown else c.muted,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Four big choices in a 2x2 grid. */
@Composable
private fun TypePicker(current: ReportType, onPick: (ReportType) -> Unit) {
    val items = listOf(
        Triple(ReportType.WRONG_NAME, InkGlyph.Pencil, R.string.report_type_wrong_name),
        Triple(ReportType.NEW_PLACE, InkGlyph.Pin, R.string.report_type_new_place),
        Triple(ReportType.CLOSED, InkGlyph.Closed, R.string.report_type_closed),
        Triple(ReportType.OTHER, InkGlyph.Spark, R.string.report_type_other),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (type, glyph, label) ->
                    TypeTile(type == current, glyph, stringResource(label), Modifier.weight(1f)) { onPick(type) }
                }
            }
        }
    }
}

@Composable
private fun TypeTile(selected: Boolean, glyph: InkGlyph, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalInk.current
    val bg by animateColorAsState(if (selected) Palette.Yellow else c.paper, tween(250), label = "tile")
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            pop.snapTo(0.92f)
            pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
        }
    }
    Column(
        modifier
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .inkSurface(bg, if (selected) Palette.Ink else c.ink, shadow = if (selected) Palette.Ink else c.shadow, radius = 18.dp, offset = 4.dp)
            .clickable(remember { MutableInteractionSource() }, null, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InkIcon(
            glyph, size = 34.dp,
            ink = if (selected) Palette.Ink else c.ink,
            // On the dark theme the ink is yellow, so the fill must differ or the drawing disappears.
            fill = when {
                selected -> Palette.Cream
                c.dark -> Palette.Graphite
                else -> Palette.Yellow
            },
        )
        Text(
            label,
            color = if (selected) Palette.Ink else c.text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LocationCard(location: ReportLocation?, status: ReportStatus, onUseLocation: () -> Unit) {
    val c = LocalInk.current
    Column(
        Modifier.fillMaxWidth().inkSurface(c.paper, c.ink, shadow = c.shadow).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(InkGlyph.Pin, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.report_location), style = MaterialTheme.typography.titleMedium, color = c.text)
        }
        AnimatedContent(targetState = location, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }, label = "loc") { loc ->
            if (loc == null) {
                Text(stringResource(R.string.report_location_none), color = c.muted, style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Coordinates are always left-to-right digits.
                    Text(
                        // U+2066/U+2069 isolate the numbers left-to-right, so Arabic layout does not swap them.
                        "\u2066" + String.format(Locale.ROOT, "%.5f, %.5f", loc.latitude, loc.longitude) + "\u2069",
                        color = c.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(
                            when (loc.source) {
                                ReportLocation.Source.SHARED -> R.string.report_location_shared
                                ReportLocation.Source.OUR_POINT -> R.string.report_location_point
                                ReportLocation.Source.DEVICE_GPS -> R.string.report_location_gps
                            },
                        ),
                        color = c.muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        val locating = status is ReportStatus.Locating
        InkButton(
            stringResource(if (locating) R.string.report_locating else R.string.report_use_gps),
            onUseLocation,
            Modifier.fillMaxWidth(),
            InkGlyph.Navigate,
            style = InkButtonStyle.Secondary,
            enabled = !locating,
        )
        AnimatedVisibility(status is ReportStatus.NoLocationFix) {
            ProblemLine(stringResource(R.string.report_no_fix))
        }
        Text(stringResource(R.string.report_pick_hint), color = c.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldsCard(draft: ReportDraft, onChange: (ReportDraft) -> Unit) {
    val c = LocalInk.current
    Column(
        Modifier.fillMaxWidth().inkSurface(c.paper, c.ink, shadow = c.shadow).padding(16.dp)
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (draft.type) {
            ReportType.WRONG_NAME -> {
                InkField(draft.currentName, { onChange(draft.copy(currentName = it)) }, stringResource(R.string.report_current_name))
                InkField(draft.newName, { onChange(draft.copy(newName = it)) }, stringResource(R.string.report_correct_name), required = true)
            }
            ReportType.NEW_PLACE -> {
                Text(stringResource(R.string.report_place_kind), color = c.text, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PLACE_KIND_LABELS.forEach { (key, label) ->
                        InkChip(stringResource(label), draft.placeKind == key) {
                            onChange(draft.copy(placeKind = if (draft.placeKind == key) "" else key))
                        }
                    }
                }
                InkField(draft.newName, { onChange(draft.copy(newName = it)) }, stringResource(R.string.report_place_name))
            }
            ReportType.CLOSED -> {
                InkField(draft.currentName, { onChange(draft.copy(currentName = it)) }, stringResource(R.string.report_place_name), required = true)
            }
            ReportType.OTHER -> Unit
        }
        InkField(
            draft.details, { onChange(draft.copy(details = it)) }, stringResource(R.string.report_details),
            required = draft.type == ReportType.OTHER, singleLine = false,
        )
    }
}

/** Direct OpenStreetMap editing: login, then add a place or pick a nearby object to rename, with a preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OsmEditCard(state: UiState, draft: ReportDraft, edit: OsmEdit?, vm: MainViewModel) {
    val c = LocalInk.current
    val osm = state.osm
    Column(
        Modifier.fillMaxWidth().inkSurface(c.paper, c.ink, shadow = c.shadow).padding(16.dp)
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (osm.user == null) {
            Text(stringResource(R.string.osm_login_text), color = c.text, style = MaterialTheme.typography.bodySmall)
            InkButton(
                stringResource(if (osm.loggingIn) R.string.osm_logging_in else R.string.osm_login),
                vm::loginOsm, Modifier.fillMaxWidth(), InkGlyph.Shield,
            )
            AnimatedVisibility(osm.loginFailed) { ProblemLine(stringResource(R.string.osm_login_failed)) }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.osm_logged_in_as, osm.user), color = c.text, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.osm_logout), color = c.muted, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = vm::logoutOsm)
                    .padding(6.dp),
            )
        }
        when (draft.type) {
            ReportType.NEW_PLACE -> {
                Text(stringResource(R.string.report_place_kind), color = c.text, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PLACE_KIND_LABELS.filter { it.first != "other" }.forEach { (key, label) ->
                        InkChip(stringResource(label), draft.placeKind == key) { vm.updateReport(draft.copy(placeKind = key)) }
                    }
                }
                InkField(draft.newName, { vm.updateReport(draft.copy(newName = it)) }, stringResource(R.string.report_place_name))
            }
            ReportType.WRONG_NAME -> {
                InkButton(
                    stringResource(if (osm.loadingNearby) R.string.osm_searching else R.string.osm_find_nearby),
                    vm::loadOsmNearby, Modifier.fillMaxWidth(), InkGlyph.Navigate, InkButtonStyle.Secondary,
                    enabled = draft.location != null && !osm.loadingNearby,
                )
                AnimatedVisibility(osm.nearbyFailed) { ProblemLine(stringResource(R.string.osm_nearby_failed)) }
                osm.nearby?.let { list ->
                    if (list.isEmpty()) Text(stringResource(R.string.osm_nearby_empty), color = c.muted, style = MaterialTheme.typography.bodySmall)
                    list.forEachIndexed { i, el ->
                        ChoiceCard(osm.selectedId == "${el.type}/${el.id}", { vm.selectOsmElement(el) }, i) {
                            Column {
                                Text(el.name ?: "—", fontWeight = FontWeight.Bold,
                                    color = if (osm.selectedId == "${el.type}/${el.id}") Palette.Ink else c.text)
                                Text(
                                    "\u2066" + listOfNotNull(el.kindHint, "${el.distanceM.toInt()} m").joinToString(" · ") + "\u2069",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (osm.selectedId == "${el.type}/${el.id}") Palette.Brown else c.muted,
                                )
                            }
                        }
                    }
                }
                if (osm.selected != null) {
                    InkField(draft.newName, { vm.updateReport(draft.copy(newName = it)) }, stringResource(R.string.report_correct_name), required = true)
                }
            }
            else -> Text(stringResource(R.string.osm_edit_use_note), color = c.text, style = MaterialTheme.typography.bodySmall)
        }
        // Exactly what will be written to OpenStreetMap.
        AnimatedVisibility(edit != null) {
            if (edit != null) OsmPreview(edit)
        }
    }
}

@Composable
private fun OsmPreview(edit: OsmEdit) {
    val c = LocalInk.current
    Column(
        Modifier.fillMaxWidth().background(if (c.dark) Palette.Graphite else Palette.Sand.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.osm_preview), color = c.text, fontWeight = FontWeight.Bold)
        // Tags are OSM keys/values: always left-to-right.
        androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val lines = when (edit) {
                    is OsmEdit.AddNode -> edit.tags.toSortedMap().map { (k, v) -> "+ $k=$v" }
                    is OsmEdit.Retag -> edit.changes.map { (k, old, new) ->
                        when {
                            old == null -> "+ $k=$new"
                            new == null -> "- $k=$old"
                            else -> "~ $k: $old → $new"
                        }
                    }
                }
                lines.forEach {
                    Text(it, color = c.text, style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                Text(edit.comment, color = c.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val PLACE_KIND_LABELS = listOf(
    "school" to R.string.kind_school,
    "pharmacy" to R.string.kind_pharmacy,
    "hospital" to R.string.kind_hospital,
    "clinic" to R.string.kind_clinic,
    "fuel" to R.string.kind_fuel,
    "bakery" to R.string.kind_bakery,
    "water" to R.string.kind_water,
    "other" to R.string.kind_other,
)

@Composable
private fun InkField(value: String, onValue: (String) -> Unit, label: String, required: Boolean = false, singleLine: Boolean = true) {
    val c = LocalInk.current
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.take(300)) },
        label = { Text(if (required) "$label *" else label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.ink, unfocusedBorderColor = c.ink.copy(alpha = 0.5f),
            focusedLabelColor = c.text, unfocusedLabelColor = c.muted, cursorColor = c.ink,
            focusedTextColor = c.text, unfocusedTextColor = c.text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CheckBox(checked: Boolean) {
    val c = LocalInk.current
    val bg by animateColorAsState(if (checked) Palette.Yellow else c.paper, tween(200), label = "cb")
    Box(
        Modifier.size(26.dp).background(bg, RoundedCornerShape(7.dp)).border(2.dp, c.ink, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(checked, enter = scaleIn(spring(dampingRatio = 0.4f)) + fadeIn(), exit = fadeOut()) {
            InkIcon(InkGlyph.Check, size = 22.dp, ink = Palette.Ink, fill = Palette.Yellow)
        }
    }
}

@Composable
private fun SentView(sent: ReportStatus.Sent, directEdit: Boolean, onDone: () -> Unit) {
    val c = LocalInk.current
    val uri = LocalUriHandler.current
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessLow)) }
    Column(
        Modifier.fillMaxSize().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            Modifier
                .size(130.dp)
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = (1f - pop.value) * -30f }
                .inkSurface(Palette.Yellow, Palette.Ink, radius = 65.dp, offset = 5.dp),
            contentAlignment = Alignment.Center,
        ) { InkIcon(InkGlyph.Check, size = 84.dp, ink = Palette.Ink, fill = Palette.Cream) }
        Text(
            stringResource(if (directEdit) R.string.osm_edit_sent_title else R.string.report_sent_title),
            style = MaterialTheme.typography.headlineSmall, color = c.text, textAlign = TextAlign.Center,
        )
        Text(
            stringResource(
                when {
                    directEdit -> R.string.osm_edit_sent_text
                    sent.community -> R.string.report_sent_team_text
                    else -> R.string.report_sent_text
                },
            ),
            style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        sent.url?.let { url ->
            InkButton(stringResource(if (directEdit) R.string.osm_view_changeset else R.string.report_view_note), { uri.openUri(url) }, Modifier.fillMaxWidth(), InkGlyph.Navigate, InkButtonStyle.Secondary)
        }
        InkButton(stringResource(R.string.report_done), onDone, Modifier.fillMaxWidth(), InkGlyph.Check)
    }
}
