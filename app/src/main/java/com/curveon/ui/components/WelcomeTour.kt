package com.curveon.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import com.curveon.AppLanguage
import com.curveon.R
import com.curveon.ui.theme.*
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/** The parts of the dashboard the welcome tour points at. */
enum class TourTarget { SEARCH, GPX, PHONE, SETTINGS, MAP_TOGGLE, MAP, MAP_BUTTONS, SPEED, CARDS }

/** Where each [TourTarget] is on screen right now; parts that aren't shown (e.g. the cards in full map) are missing. */
class TourTargets {
    internal val bounds = mutableStateMapOf<TourTarget, Rect>()
}

val LocalTourTargets = staticCompositionLocalOf<TourTargets?> { null }

/** Marks this element as [target], so the welcome tour can light it up. */
fun Modifier.tourTarget(target: TourTarget): Modifier = composed {
    val targets = LocalTourTargets.current ?: return@composed Modifier
    DisposableEffect(targets, target) { onDispose { targets.bounds.remove(target) } }
    Modifier.onGloballyPositioned { c ->
        val r = c.boundsInRoot()
        if (targets.bounds[target] != r) targets.bounds[target] = r
    }
}

/** A symbol from the app and what it does: an icon, or a few letters like the compass's "N" and "3D". */
class HelpSymbol(val icon: ImageVector?, val glyph: String?, val tint: Color, @StringRes val text: Int, val filled: Boolean = false)

private fun sym(icon: ImageVector, tint: Color, @StringRes text: Int, filled: Boolean = false) = HelpSymbol(icon, null, tint, text, filled)
private fun glyph(g: String, @StringRes text: Int) = HelpSymbol(null, g, ElectricCyan, text)

private val RAIL_SYMBOLS = listOf(
    sym(Icons.Default.TwoWheeler, ElectricCyan, R.string.help_where_to),
    sym(Icons.Default.Route, ElectricCyan, R.string.help_gpx),
    sym(Icons.Default.Phone, EcoGreen, R.string.help_phone),
    sym(Icons.Default.Settings, TextPrimary, R.string.settings),
    sym(Icons.Default.Map, ElectricCyan, R.string.help_full_map),
    sym(Icons.Default.Dashboard, ElectricCyan, R.string.help_dashboard)
)
private val MAP_SYMBOLS = listOf(
    glyph("N", R.string.help_north_up),
    sym(Icons.Default.Navigation, ElectricCyan, R.string.help_heading_up),
    glyph("3D", R.string.help_3d),
    sym(Icons.Default.Add, TextPrimary, R.string.help_zoom_in),
    sym(Icons.Default.Remove, TextPrimary, R.string.help_zoom_out),
    sym(Icons.Default.MyLocation, ElectricCyan, R.string.help_follow_me, filled = true),
    sym(Icons.Default.ZoomOutMap, TextPrimary, R.string.help_fit_trip)
)
private val PLAN_SYMBOLS = listOf(
    sym(Icons.Default.Tune, NeonAmber, R.string.help_adjust),
    sym(Icons.Default.TripOrigin, TextSecondary, R.string.help_start),
    sym(Icons.Default.Gesture, ElectricCyan, R.string.help_gesture),
    sym(Icons.Default.Shuffle, TextPrimary, R.string.help_shuffle),
    sym(Icons.Default.SwapHoriz, TextPrimary, R.string.help_reverse),
    sym(Icons.Default.BookmarkBorder, TextPrimary, R.string.help_save),
    sym(Icons.Default.ExpandMore, TextPrimary, R.string.help_collapse),
    sym(Icons.Default.Close, TextPrimary, R.string.help_close_plan)
)
private val EDIT_SYMBOLS = listOf(
    sym(Icons.Default.LinearScale, TextPrimary, R.string.help_mark),
    sym(Icons.Default.Undo, TextPrimary, R.string.help_undo),
    sym(Icons.Default.Check, ElectricCyan, R.string.help_done, filled = true)
)
/** The give way sign's outline (a triangle pointing down), for the list of symbols. */
private val GiveWayOutline: ImageVector by lazy {
    ImageVector.Builder("GiveWay", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M2,4 L22,4 L12,21.3 Z M6.2,6.4 L12,16.4 L17.8,6.4 Z"
            ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
            pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        )
    }.build()
}

private val RIDE_SYMBOLS = listOf(
    sym(Icons.Default.AddLocationAlt, NeonAmber, R.string.help_add_stop),
    sym(Icons.Default.Close, WarningRed, R.string.help_end_trip),
    sym(Icons.Default.LocalGasStation, EcoGreen, R.string.help_fuel),
    sym(Icons.Default.PhotoCamera, NeonAmber, R.string.help_camera),
    sym(GiveWayOutline, WarningRed, R.string.help_give_way)
)

private val GESTURE_SYMBOLS = listOf(
    sym(Icons.Default.TouchApp, ElectricCyan, R.string.help_hold_map),
    sym(Icons.Default.Gesture, ElectricCyan, R.string.help_hold_line),
    sym(Icons.Default.Draw, NeonAmber, R.string.help_draw),
    sym(Icons.Default.Swipe, TextPrimary, R.string.help_swipe_message),
    sym(Icons.Default.SwipeLeft, TextPrimary, R.string.help_swipe_music)
)

/** Every symbol of the app, by where it appears: Settings → Help lists them all. */
val HELP_GROUPS: List<Pair<Int, List<HelpSymbol>>> = listOf(
    R.string.help_group_rail to RAIL_SYMBOLS,
    R.string.help_group_map to MAP_SYMBOLS,
    R.string.help_group_plan to PLAN_SYMBOLS,
    R.string.help_group_edit to EDIT_SYMBOLS,
    R.string.help_group_ride to RIDE_SYMBOLS,
    R.string.help_group_gestures to GESTURE_SYMBOLS
)

private val BluetoothAccent = Color(0xFF5C9DFF)

private class TourStep(
    val target: TourTarget?,
    val icon: ImageVector,
    val accent: Color,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val symbols: List<HelpSymbol> = emptyList(),
    val welcome: Boolean = false,
    /** First start only: the language comes first, so the rest is read in it. */
    val language: Boolean = false,
    val art: TourArt? = null
)

private val STEPS = listOf(
    TourStep(null, Icons.Default.Public, ElectricCyan, R.string.settings_language, R.string.settings_language, language = true),
    TourStep(null, Icons.Default.TwoWheeler, ElectricCyan, R.string.tour_welcome_title, R.string.tour_welcome_body, welcome = true),
    TourStep(TourTarget.SEARCH, Icons.Default.TwoWheeler, ElectricCyan, R.string.where_to, R.string.tour_search_body),
    TourStep(TourTarget.GPX, Icons.Default.Route, ElectricCyan, R.string.gpx_manager, R.string.tour_gpx_body),
    TourStep(TourTarget.PHONE, Icons.Default.Phone, EcoGreen, R.string.phone, R.string.tour_phone_body),
    TourStep(TourTarget.SETTINGS, Icons.Default.Settings, TextPrimary, R.string.settings, R.string.tour_settings_body),
    TourStep(TourTarget.MAP_TOGGLE, Icons.Default.Map, ElectricCyan, R.string.tour_toggle_title, R.string.tour_toggle_body),
    TourStep(TourTarget.MAP, Icons.Default.TouchApp, ElectricCyan, R.string.tour_map_title, R.string.tour_map_body,
        listOf(GESTURE_SYMBOLS[0])),
    TourStep(TourTarget.MAP_BUTTONS, Icons.Default.Explore, ElectricCyan, R.string.tour_buttons_title, R.string.tour_buttons_body,
        MAP_SYMBOLS.take(6)),
    TourStep(TourTarget.SPEED, Icons.Default.Speed, NeonAmber, R.string.tour_speed_title, R.string.tour_speed_body),
    TourStep(TourTarget.CARDS, Icons.Default.MusicNote, ElectricCyan, R.string.tour_cards_title, R.string.tour_cards_body, art = TourArt.CARDS),
    TourStep(null, Icons.Default.Speed, NeonAmber, R.string.tour_engine_title, R.string.tour_engine_body, art = TourArt.ENGINE),
    TourStep(null, Icons.Default.Bluetooth, BluetoothAccent, R.string.tour_setup_title, R.string.tour_setup_body, art = TourArt.SETUP),
    TourStep(null, Icons.Default.PhoneAndroid, EcoGreen, R.string.tour_solo_title, R.string.tour_solo_body, art = TourArt.SOLO),
    TourStep(TourTarget.SEARCH, Icons.Default.TwoWheeler, ElectricCyan, R.string.tour_trip_title, R.string.tour_trip_body, art = TourArt.PLAN),
    TourStep(null, Icons.Default.Gesture, NeonAmber, R.string.tour_edit_title, R.string.tour_edit_body, art = TourArt.EDIT),
    TourStep(null, Icons.Default.LinearScale, NeonAmber, R.string.tour_mark_title, R.string.tour_mark_body, art = TourArt.MARK),
    TourStep(TourTarget.SEARCH, Icons.Default.Draw, NeonAmber, R.string.tour_loop_title, R.string.tour_loop_body, art = TourArt.LOOP),
    TourStep(null, Icons.Default.AltRoute, NeonAmber, R.string.help_group_plan, R.string.tour_plan_body, PLAN_SYMBOLS),
    TourStep(null, Icons.Default.Navigation, EcoGreen, R.string.help_group_ride, R.string.tour_ride_body, RIDE_SYMBOLS),
    TourStep(TourTarget.SETTINGS, Icons.Default.HelpOutline, ElectricCyan, R.string.tour_done_title, R.string.tour_done_body)
)

/**
 * The guided welcome: the screen dims, a soft-edged hole glides from one part of the dashboard to
 * the next, and a card beside it says what that part does. Steps whose part isn't on screen are left
 * out. Shown once on the first start, and again from Settings → Help.
 */
@Composable
fun WelcomeTour(targets: TourTargets, firstStart: Boolean, onFinish: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // The tour itself is shown lying down, so the parts it points at stay put (a phone held upright turns once, when
    // "Start" is pressed: the language, the welcome and Android's questions come the way the phone is held); back to
    // the rider's own choice afterwards.
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var lying by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(activity, lying) {
        val on = lying
        val before = activity?.requestedOrientation
        if (on) activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (on && before != null) activity.requestedOrientation = before }
    }
    val landscapeNow = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // Held upright when the tour opened: it says the phone can be turned back afterwards.
    val startedUpright = rememberSaveable { !landscapeNow }
    // The parts on screen once the dashboard is laid out; then fixed, so the step count doesn't jump (looked for
    // again once the screen has turned for the tour).
    var found by remember { mutableStateOf<List<TourStep>?>(null) }
    // Picking a language restarts the screen: the tour carries on from the welcome, in that language.
    var languageChosen by rememberSaveable { mutableStateOf(false) }
    fun look() {
        val askLanguage = firstStart && AppLanguage.canChoose && !languageChosen
        found = STEPS.filter { (!it.language || askLanguage) && (it.target == null || it.target in targets.bounds) }
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withTimeoutOrNull(1500) { snapshotFlow { targets.bounds.size }.first { it >= 6 } }
        look()
    }
    // Turning for the tour: the card waits until the dashboard is laid out lying down.
    var turning by remember { mutableStateOf(false) }
    LaunchedEffect(turning, landscapeNow) { if (turning && landscapeNow) { kotlinx.coroutines.delay(700); look(); turning = false } }
    val steps = found ?: return
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[index.coerceIn(steps.indices)]
    val last = index == steps.lastIndex
    // Steps before the first numbered one: the language, and the welcome.
    val lead = steps.indexOfFirst { !it.welcome && !it.language }.coerceAtLeast(1)
    val next: () -> Unit = { if (last) onFinish() else index++ }
    val start: () -> Unit = { if (!landscapeNow) turning = true; lying = true; next() }
    androidx.activity.compose.BackHandler { if (index > 0) index-- else onFinish() }

    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(450), label = "tourFade")

    val holePad = with(density) { 8.dp.toPx() }
    // Kept just inside the screen, so the ring round a part at the edge (the rail) isn't cut off.
    val edge = with(density) { 3.dp.toPx() }
    val targetRect = step.target?.let { targets.bounds[it] }?.translate(-origin)?.inflate(holePad)
        ?.intersect(Rect(edge, edge, screen.width - edge, screen.height - edge))
    val center = Offset(screen.width / 2f, screen.height / 2f)
    // Without a part to show, the hole closes to a point in the middle (and opens from there).
    val hole by animateRectAsState(targetRect ?: Rect(center, 0f), spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow), label = "tourHole")
    val ring by animateFloatAsState(if (targetRect != null) 1f else 0f, tween(400), label = "tourRing")
    val pulse by rememberInfiniteTransition(label = "tourPulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearOutSlowInEasing), RepeatMode.Restart), label = "tourPulseValue"
    )

    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot(); screen = it.size }
            // The dashboard underneath stays still while the tour is open.
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag("welcome_tour")
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = with(density) { 20.dp.toPx() }
            val dim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRoundRect(RoundRect(hole, CornerRadius(radius)))
            }
            drawPath(dim, Color(0xFF05080D).copy(alpha = 0.80f * fade))
            if (ring > 0f && !hole.isEmpty) {
                val stroke = with(density) { 2.dp.toPx() }
                drawRoundRect(ElectricCyan.copy(alpha = 0.9f * ring * fade), hole.topLeft, hole.size, CornerRadius(radius), Stroke(stroke))
                // A slow ripple outwards, so the eye finds the lit part.
                val grow = with(density) { 14.dp.toPx() } * pulse
                val outer = hole.inflate(grow)
                drawRoundRect(ElectricCyan.copy(alpha = 0.45f * (1f - pulse) * ring * fade), outer.topLeft, outer.size,
                    CornerRadius(radius + grow), Stroke(stroke))
            }
        }

        val margin = with(density) { 16.dp.toPx() }
        val gap = with(density) { 14.dp.toPx() }
        // A long list of symbols gets a wider card with two columns, so it fits the height.
        val maxCard = with(density) { (if (step.welcome || step.language || step.art != null) 460.dp else if (step.symbols.size > 4) 660.dp else 400.dp).toPx() }
        val maxHeight = with(density) { (screen.height - 2 * margin).coerceAtLeast(0f).toDp() }
        val cardWidth = with(density) { minOf(maxCard, screen.width - 2 * margin).coerceAtLeast(0f).toDp() }
        val place = cardPlacement(targetRect, screen, cardSize, margin, gap)
        val offset by animateIntOffsetAsState(place, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow), label = "tourCard")

        Column(
            Modifier.offset { offset }.width(cardWidth).heightIn(max = maxHeight)
                .onSizeChanged { cardSize = it }
                .graphicsLayer { alpha = if (cardSize == IntSize.Zero || turning) 0f else fade }
                .shadow(28.dp, CardShape, ambientColor = ElectricCyan.copy(alpha = 0.4f), spotColor = Color.Black)
                .clip(CardShape)
                .background(Brush.verticalGradient(listOf(CockpitSurfaceVariant, CockpitSurface)))
                .border(1.dp, Brush.linearGradient(listOf(step.accent.copy(alpha = 0.7f), CockpitBorder, CockpitBorder)), CardShape)
                .padding(20.dp)
        ) {
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(260, 90)) + slideInHorizontally(tween(320)) { it / 8 * dir }) togetherWith
                        (fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { -it / 8 * dir })
                },
                label = "tourStep",
                // Scrolls only if a phone's height can't hold it all.
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            ) { i ->
                val s = steps[i.coerceIn(steps.indices)]
                when {
                    s.language -> LanguageContent(AppLanguage.current(context)) { picked ->
                        if (picked == AppLanguage.current(context)) index++
                        else { languageChosen = true; AppLanguage.set(context, picked) }
                    }
                    s.welcome -> WelcomeContent(s)
                    else -> StepContent(s, i - lead + 1, steps.size - lead, columns = if (cardWidth >= 560.dp) 2 else 1)
                }
            }
            // Turned on its side for the tour only.
            if (startedUpright && lying && index == lead) Text(
                stringResource(R.string.tour_turn_after), color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step.language) {
                    // The two languages are the buttons.
                } else if (step.welcome) {
                    TourTextButton(stringResource(R.string.tour_not_now), TextMuted, onFinish)
                    Spacer(Modifier.weight(1f))
                    TourPrimaryButton(stringResource(R.string.tour_start), start)
                } else {
                    StepDots(steps.size - lead, index - lead + 1, Modifier.weight(1f))
                    if (!last) TourTextButton(stringResource(R.string.tour_skip), TextMuted, onFinish)
                    if (index > lead) TourTextButton(stringResource(R.string.tour_back), TextSecondary) { index-- }
                    Spacer(Modifier.width(6.dp))
                    TourPrimaryButton(stringResource(if (last) R.string.tour_done else R.string.tour_next), next)
                }
            }
        }
    }
}

/**
 * Where the card goes: beside the lit part (right, left, below or above, whichever has room), or in
 * the middle when there is no part or it is too big to sit beside (the map).
 */
private fun cardPlacement(target: Rect?, screen: IntSize, card: IntSize, margin: Float, gap: Float): IntOffset {
    val w = card.width.toFloat()
    val h = card.height.toFloat()
    val middle = IntOffset(((screen.width - w) / 2).roundToInt(), ((screen.height - h) / 2).roundToInt())
    if (target == null || card == IntSize.Zero) return middle
    fun clampX(x: Float) = x.coerceIn(margin, (screen.width - w - margin).coerceAtLeast(margin))
    fun clampY(y: Float) = y.coerceIn(margin, (screen.height - h - margin).coerceAtLeast(margin))
    val fitsH = h + 2 * margin <= screen.height
    val fitsW = w + 2 * margin <= screen.width
    val spots = listOf(
        (target.right + gap + w + margin <= screen.width && fitsH) to Offset(target.right + gap, clampY(target.center.y - h / 2)),
        (target.left - gap - w - margin >= 0 && fitsH) to Offset(target.left - gap - w, clampY(target.center.y - h / 2)),
        (target.bottom + gap + h + margin <= screen.height && fitsW) to Offset(clampX(target.center.x - w / 2), target.bottom + gap),
        (target.top - gap - h - margin >= 0 && fitsW) to Offset(clampX(target.center.x - w / 2), target.top - gap - h)
    )
    val spot = spots.firstOrNull { it.first }?.second ?: return middle
    return IntOffset(spot.x.roundToInt(), spot.y.roundToInt())
}

@Composable
private fun WelcomeContent(step: TourStep) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        WindingRoad(Modifier.fillMaxWidth().height(96.dp))
        Spacer(Modifier.height(10.dp))
        Text(stringResource(step.title), color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Black,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(step.body), color = TextSecondary, fontSize = 15.sp, lineHeight = 22.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Shown before anything else on the first start: each language in its own words. */
@Composable
private fun LanguageContent(current: AppLanguage, onPick: (AppLanguage) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Public, null, tint = ElectricCyan, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text("Language · Språk", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(18.dp))
        // Three to a row, the last row filled out so all are as wide.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            AppLanguage.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { lang ->
                        val on = lang == current
                        Box(
                            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp))
                                .background(if (on) ElectricCyan else CockpitBackground.copy(alpha = 0.7f))
                                .border(1.dp, if (on) ElectricCyan else CockpitBorder, RoundedCornerShape(24.dp))
                                .clickable { onPick(lang) }.testTag("tour_lang_${lang.tag}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(lang.label, color = if (on) Color(0xFF00363D) else TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun StepContent(step: TourStep, number: Int, count: Int, columns: Int) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(step.accent.copy(alpha = 0.14f))
                    .border(1.dp, step.accent.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(step.icon, null, tint = step.accent, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(R.string.tour_step, number, count).uppercase(), color = TextMuted, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(stringResource(step.title), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 2)
            }
        }
        if (step.art != null) {
            Spacer(Modifier.height(12.dp))
            TourArtView(step.art)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(step.body), color = TextSecondary, fontSize = 15.sp, lineHeight = 21.sp)
        if (step.symbols.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                step.symbols.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { HelpSymbolRow(it, Modifier.weight(1f), compact = true) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** A symbol as it looks in the app, and what it does. */
@Composable
fun HelpSymbolRow(symbol: HelpSymbol, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val size = if (compact) 34.dp else 42.dp
        Box(
            Modifier.size(size).clip(RoundedCornerShape(if (compact) 10.dp else 12.dp))
                .background(if (symbol.filled) symbol.tint else CockpitBackground.copy(alpha = 0.7f))
                .border(1.dp, if (symbol.filled) symbol.tint else CockpitBorder, RoundedCornerShape(if (compact) 10.dp else 12.dp)),
            contentAlignment = Alignment.Center
        ) {
            val color = if (symbol.filled) Color(0xFF00363D) else symbol.tint
            if (symbol.icon != null) Icon(symbol.icon, null, tint = color, modifier = Modifier.size(if (compact) 20.dp else 24.dp))
            else Text(symbol.glyph.orEmpty(), color = color, fontSize = if (compact) 15.sp else 17.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(symbol.text), color = TextPrimary, fontSize = if (compact) 14.sp else 15.sp, lineHeight = if (compact) 18.sp else 20.sp)
    }
}

@Composable
private fun StepDots(count: Int, index: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..count) {
            val on = i == index
            val width by animateDpAsState(if (on) 18.dp else 6.dp, spring(stiffness = Spring.StiffnessMediumLow), label = "dot")
            val color by animateColorAsState(if (on) ElectricCyan else if (i < index) TextSecondary else CockpitBorder, label = "dotColor")
            Box(Modifier.height(6.dp).width(width).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun TourTextButton(text: String, color: Color, onClick: () -> Unit) {
    Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp))
}

@Composable
private fun TourPrimaryButton(text: String, onClick: () -> Unit) {
    Row(
        Modifier.height(46.dp).clip(RoundedCornerShape(23.dp))
            .background(Brush.horizontalGradient(listOf(ElectricCyan, Color(0xFF4DF0FF))))
            .clickable(onClick = onClick).padding(start = 20.dp, end = 16.dp).testTag("tour_next"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = Color(0xFF00363D), fontSize = 15.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Default.ArrowForward, null, tint = Color(0xFF00363D), modifier = Modifier.size(18.dp))
    }
}

/** A road winding across the welcome card, drawn in as it opens, with the rider's dot at its end. */
@Composable
private fun WindingRoad(modifier: Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(2200, delayMillis = 250, easing = FastOutSlowInEasing)) }
    val glow by rememberInfiniteTransition(label = "roadGlow").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "roadGlowValue"
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val road = Path().apply {
            moveTo(w * 0.04f, h * 0.78f)
            cubicTo(w * 0.18f, h * 0.78f, w * 0.20f, h * 0.20f, w * 0.34f, h * 0.22f)
            cubicTo(w * 0.48f, h * 0.24f, w * 0.44f, h * 0.84f, w * 0.60f, h * 0.80f)
            cubicTo(w * 0.74f, h * 0.76f, w * 0.72f, h * 0.18f, w * 0.96f, h * 0.24f)
        }
        val width = 4.dp.toPx()
        drawPath(road, CockpitBorder, style = Stroke(width * 2.4f, cap = StrokeCap.Round))
        drawPath(road, TextMuted.copy(alpha = 0.5f), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 7.dp.toPx()))))
        val measure = PathMeasure().apply { setPath(road, false) }
        val done = Path()
        val length = measure.length * progress.value
        if (length > 0f) {
            measure.getSegment(0f, length, done, true)
            drawPath(done, Brush.horizontalGradient(listOf(ElectricCyan, NeonAmber)), style = Stroke(width, cap = StrokeCap.Round))
            val head = measure.getPosition(length)
            val tint = androidx.compose.ui.graphics.lerp(ElectricCyan, NeonAmber, head.x / w)
            drawCircle(tint.copy(alpha = 0.25f * glow), 14.dp.toPx(), head)
            drawCircle(tint, 6.dp.toPx(), head)
            drawCircle(CockpitBackground, 2.5.dp.toPx(), head)
        }
    }
}

private val CardShape = RoundedCornerShape(24.dp)
