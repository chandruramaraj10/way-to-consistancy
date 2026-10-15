package com.waytoconsistency

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import org.json.JSONArray

class RunMapActivity : Activity(), OnMapReadyCallback {
    private lateinit var mapView: MapView
    private data class RoutePoint(val coordinate: LatLng, val segment: Int)
    private var routePoints = emptyList<RoutePoint>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Run route"
        routePoints = parsePoints(intent.getStringExtra(EXTRA_POINTS))
        val root = FrameLayout(this)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        if (routePoints.size < 2) {
            val message = TextView(this).apply {
                text = "Not enough GPS points to draw a route yet. Keep Location enabled and wait for an accurate fix."
                setTextColor(Color.DKGRAY)
                setBackgroundColor(Color.WHITE)
                setPadding(28, 20, 28, 20)
                gravity = Gravity.CENTER
            }
            root.addView(message, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }
        setContentView(root)
    }

    override fun onMapReady(map: GoogleMap) {
        map.uiSettings.isMapToolbarEnabled = true
        map.uiSettings.isZoomControlsEnabled = true
        if (routePoints.isEmpty()) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(20.0, 0.0), 1.5f))
            return
        }
        val points = routePoints.map { it.coordinate }
        val routeSegments = routePoints.groupBy { it.segment }.values.map { segment -> segment.map { it.coordinate } }
        routeSegments.filter { it.size > 1 }.forEach { segment ->
            map.addPolyline(PolylineOptions().addAll(segment).color(Color.rgb(28, 95, 209)).width(10f).geodesic(true))
        }
        map.addMarker(MarkerOptions().position(points.first()).title("Start").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
        if (points.size > 1) {
            map.addMarker(MarkerOptions().position(points.last()).title("Finish"))
            val bounds = LatLngBounds.builder().apply { points.forEach(::include) }.build()
            map.setOnMapLoadedCallback { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 72)) }
        } else map.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 16f))
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    private fun parsePoints(json: String?): List<RoutePoint> = try {
        val array = JSONArray(json ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val point = array.optJSONObject(index) ?: return@mapNotNull null
            val latitude = point.optDouble("lat", Double.NaN)
            val longitude = point.optDouble("lon", Double.NaN)
            if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                RoutePoint(LatLng(latitude, longitude), point.optInt("segment", 0))
            } else null
        }
    } catch (_: Exception) { emptyList() }

    companion object {
        const val EXTRA_POINTS = "routePoints"
    }
}
