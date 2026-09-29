package ca.airspacemonitor.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ca.airspacemonitor.R
import ca.airspacemonitor.domain.GeoPoint
import ca.airspacemonitor.domain.Tier
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

data class AircraftPin(
    val hex: String,
    val position: GeoPoint,
    val trackDeg: Double?,
    val callsign: String?,
    val label: String?,
)

sealed interface FenceSpec {
    val tier: Tier
    /** Grey reference-only rendering (e.g. the warning zone shown inside the watch editor map). */
    val grey: Boolean get() = false
    data class Circle(
        val center: GeoPoint,
        val radiusKm: Double,
        override val tier: Tier = Tier.WATCH,
        override val grey: Boolean = false,
    ) : FenceSpec
    data class Poly(
        val points: List<GeoPoint>,
        override val tier: Tier = Tier.WATCH,
        override val grey: Boolean = false,
    ) : FenceSpec
}

/** Tile source built from a "{z}/{x}/{y}" URL template (settings override). */
private class TemplateTileSource(
    name: String,
    private val template: String,
) : OnlineTileSourceBase(name, 0, 19, 256, ".png", arrayOf("")) {
    override fun getTileURLString(pMapTileIndex: Long): String = template
        .replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
        .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
        .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
}

private fun tileSource(template: String) =
    if (template.isBlank()) TileSourceFactory.MAPNIK else TemplateTileSource("custom", template.trim())

/**
 * Camera state (center + zoom) keyed by [AirspaceMap.mapKey], surviving the
 * composable leaving composition (tab switches). The MapView object itself is
 * NOT retained: osmdroid tears it down when it leaves the window hierarchy
 * (MapViewRepository.onDetach nulls its MapView), and reusing such an instance
 * crashes overlay construction with an NPE.
 */
private data class MapCamera(val center: GeoPoint?, val zoom: Double)

private val mapCamerasByKey = HashMap<String, MapCamera>()

@Composable
fun AirspaceMap(
    modifier: Modifier = Modifier,
    tileTemplate: String = "",
    fences: List<FenceSpec> = emptyList(),
    aircraft: List<AircraftPin> = emptyList(),
    trails: Map<String, List<GeoPoint>> = emptyMap(),
    gpsPoint: GeoPoint? = null,
    /** Voice-reference marker (the "marked point" announcement setting). */
    refPoint: GeoPoint? = null,
    /** Polygon editor vertices (draggable-look markers; deletion is long-press). */
    vertices: List<GeoPoint> = emptyList(),
    onMapTap: ((GeoPoint) -> Unit)? = null,
    onMapLongPress: ((GeoPoint) -> Unit)? = null,
    /** Recenter whenever the object identity changes (e.g. a "recenter" button click counter). */
    recenterRequest: Any? = null,
    recenterPoint: GeoPoint? = null,
    initialCenter: GeoPoint? = null,
    initialZoom: Double = 14.5,
    /** Retain this map (zoom/pan) across composable disposal, keyed per screen. */
    mapKey: String? = null,
    /** When non-empty, a recenter request zooms to fit these points instead of [recenterPoint]. */
    fitPoints: List<GeoPoint> = emptyList(),
    /** Invoked when the user taps an aircraft pin. */
    onAircraftTap: ((AircraftPin) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    // Captured at creation so a late-arriving initial center is still honored.
    val creationCenter = remember { mutableStateOf<GeoPoint?>(null) }
    if (creationCenter.value == null && initialCenter != null) {
        creationCenter.value = initialCenter
    }

    val mapView = remember(mapKey) {
        MapView(context).apply {
            setTileSource(tileSource(tileTemplate))
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            val saved = mapKey?.let { mapCamerasByKey[it] }
            if (saved != null) {
                saved.center?.let { controller.setCenter(OsmGeoPoint(it.lat, it.lon)) }
                controller.setZoom(saved.zoom)
            } else {
                creationCenter.value?.let { controller.setCenter(OsmGeoPoint(it.lat, it.lon)) }
                controller.setZoom(initialZoom)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            // Remember where the user left the map so the next composition
            // restores pan/zoom instead of snapping back to defaults. Skip
            // before-layout disposals: the camera would still be the (0,0)
            // default and would poison the restore.
            if (mapKey != null && mapView.width > 0 && mapView.height > 0) {
                mapCamerasByKey[mapKey] = MapCamera(
                    center = GeoPoint(mapView.mapCenter.latitude, mapView.mapCenter.longitude),
                    zoom = mapView.zoomLevelDouble,
                )
            }
            mapView.onDetach()
        }
    }

    LaunchedEffect(tileTemplate) {
        mapView.setTileSource(tileSource(tileTemplate))
    }

    LaunchedEffect(fences, aircraft, trails, gpsPoint, refPoint, vertices, onMapTap, onMapLongPress, onAircraftTap) {
        val overlays = mapView.overlayManager
        overlays.clear()
        fences.forEach { overlays.add(fenceOverlay(context, mapView, it)) }
        for ((_, points) in trails) {
            if (points.size >= 2) overlays.add(trailOverlay(points))
        }
        gpsPoint?.let { overlays.add(gpsMarker(context, mapView, it)) }
        refPoint?.let { overlays.add(refMarker(context, mapView, it)) }
        vertices.forEach { v ->
            overlays.add(
                Marker(mapView).apply {
                    position = OsmGeoPoint(v.lat, v.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = ContextCompat.getDrawable(context, R.drawable.ic_vertex)
                    title = "Vertex"
                },
            )
        }
        aircraft.forEach { pin ->
            overlays.add(
                Marker(mapView).apply {
                    position = OsmGeoPoint(pin.position.lat, pin.position.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    // The plane is already rotated inside the icon bitmap; setting
                    // Marker.rotation would rotate the bitmap again and tilt the label.
                    icon = BitmapDrawable(mapView.resources, aircraftIcon(context, pin))
                    pin.callsign?.let { title = it }
                    pin.label?.let { subDescription = it }
                    setOnMarkerClickListener { _, _ ->
                        onAircraftTap?.invoke(pin)
                        true
                    }
                },
            )
        }
        // Rebuild the gesture overlay alongside everything else so it never goes stale.
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: OsmGeoPoint): Boolean {
                onMapTap?.invoke(GeoPoint(p.latitude, p.longitude))
                return true
            }

            override fun longPressHelper(p: OsmGeoPoint): Boolean {
                onMapLongPress?.invoke(GeoPoint(p.latitude, p.longitude))
                return true
            }
        }
        overlays.add(MapEventsOverlay(receiver))
        mapView.invalidate()
    }

    // Only act on *changes* — the effect also fires on first composition, and
    // re-fitting there would discard the user's pan/zoom on every tab return.
    val handledRequest = remember { mutableStateOf<Any?>(recenterRequest) }
    LaunchedEffect(recenterRequest) {
        if (recenterRequest == handledRequest.value) return@LaunchedEffect
        handledRequest.value = recenterRequest
        when {
            fitPoints.isNotEmpty() ->
                mapView.zoomToBoundingBox(fitBoundingBox(fitPoints), true)
            recenterPoint != null ->
                mapView.controller.animateTo(OsmGeoPoint(recenterPoint.lat, recenterPoint.lon))
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { mapView },
        )

        // OSM tile usage policy requires visible attribution (default MAPNIK tiles only).
        if (tileTemplate.isBlank()) {
            Text(
                "© OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 4.dp)
                    .background(
                        Color.White.copy(alpha = 0.65f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** Bounding box around the given points, padded so a tight zone doesn't hug the screen edge. */
private fun fitBoundingBox(points: List<GeoPoint>): org.osmdroid.util.BoundingBox {
    var minLat = 90.0
    var maxLat = -90.0
    var minLon = 180.0
    var maxLon = -180.0
    for (p in points) {
        minLat = minOf(minLat, p.lat)
        maxLat = maxOf(maxLat, p.lat)
        minLon = minOf(minLon, p.lon)
        maxLon = maxOf(maxLon, p.lon)
    }
    val latPad = (maxLat - minLat).coerceAtLeast(0.01) * 0.25 + 0.01
    val lonPad = (maxLon - minLon).coerceAtLeast(0.01) * 0.25 + 0.01
    return org.osmdroid.util.BoundingBox(maxLat + latPad, maxLon + lonPad, minLat - latPad, minLon - lonPad)
}

private fun fenceOverlay(context: Context, mapView: MapView, fence: FenceSpec): Polygon =
    Polygon(mapView).apply {
        val accent = when {
            fence.grey -> 0xFF9E9E9E.toInt()
            fence.tier == Tier.WARNING -> 0xFFE53935.toInt()
            else -> 0xFF0055C8.toInt()
        }
        outlinePaint.color = accent
        outlinePaint.strokeWidth = when {
            fence.grey -> 3f
            fence.tier == Tier.WARNING -> 5f
            else -> 4f
        }
        fillPaint.color = (accent and 0x00FFFFFF) or 0x22000000
        when (fence) {
            is FenceSpec.Circle -> points = circlePoints(fence.center, fence.radiusKm, 96)
            is FenceSpec.Poly -> points = fence.points.map { OsmGeoPoint(it.lat, it.lon) } +
                OsmGeoPoint(fence.points.first().lat, fence.points.first().lon)
        }
    }

fun circlePoints(center: GeoPoint, radiusKm: Double, segments: Int): List<OsmGeoPoint> {
    val result = ArrayList<OsmGeoPoint>(segments + 1)
    for (i in 0..segments) {
        val bearing = 360.0 * i / segments
        val p = ca.airspacemonitor.domain.GeoMath.destinationPoint(center, bearing, radiusKm)
        result.add(OsmGeoPoint(p.lat, p.lon))
    }
    return result
}

private fun trailOverlay(points: List<GeoPoint>): Polyline =
    Polyline().apply {
        setPoints(points.map { OsmGeoPoint(it.lat, it.lon) })
        outlinePaint.color = 0xFFEE6C00.toInt()
        outlinePaint.strokeWidth = 6f
    }

private fun gpsMarker(context: Context, mapView: MapView, point: GeoPoint): Marker =
    Marker(mapView).apply {
        position = OsmGeoPoint(point.lat, point.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = ContextCompat.getDrawable(context, R.drawable.ic_gps_dot)
        title = "You (last GPS fix)"
    }

private fun refMarker(context: Context, mapView: MapView, point: GeoPoint): Marker =
    Marker(mapView).apply {
        position = OsmGeoPoint(point.lat, point.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = ContextCompat.getDrawable(context, R.drawable.ic_vertex)
        title = "Voice reference point"
    }

private fun aircraftIcon(context: Context, pin: AircraftPin): Bitmap {
    val density = context.resources.displayMetrics.density
    val planeSizePx = (34 * density).toInt().coerceAtLeast(34)
    val plane = rotateDrawable(
        ContextCompat.getDrawable(context, R.drawable.ic_aircraft)!!,
        (pin.trackDeg ?: 0.0).toFloat(),
        planeSizePx,
    )
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202020.toInt()
        textSize = 12 * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    val label = pin.label ?: ""
    val textWidth = if (label.isEmpty()) 0f else labelPaint.measureText(label)
    val width = maxOf(planeSizePx, textWidth.toInt() + (8 * density).toInt())
    val height = planeSizePx + (label.takeIf { it.isNotEmpty() }?.let { (22 * density).toInt() } ?: 0)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val planeLeft = (width - planeSizePx) / 2f
    canvas.drawBitmap(plane, planeLeft, 0f, null)
    if (label.isNotEmpty()) {
        // Backing pill keeps the label legible when it overlaps another pin's label.
        val pad = 3 * density
        val bg = RectF(
            width / 2f - textWidth / 2f - pad,
            planeSizePx + 2 * density,
            width / 2f + textWidth / 2f + pad,
            planeSizePx + 18 * density,
        )
        canvas.drawRoundRect(bg, 4 * density, 4 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
        })
        canvas.drawText(label, width / 2f, height - 6f * density, labelPaint)
    }
    return bitmap
}

private fun rotateDrawable(drawable: Drawable, degrees: Float, sizePx: Int): Bitmap {
    val source = drawable.toBitmap(sizePx, sizePx)
    if (degrees % 360f == 0f) return source
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(source, 0, 0, sizePx, sizePx, matrix, true)
}