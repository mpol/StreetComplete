package de.westnordost.streetcomplete.screens.main.map.components

import android.annotation.SuppressLint
import android.content.Context
import de.westnordost.streetcomplete.R
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/** Takes care of showing the location + direction + accuracy marker on the map */
@SuppressLint("MissingPermission")
class CurrentLocationMapComponent(context: Context, mapStyle: Style, private val map: MapLibreMap) {

    /** Whether the whole thing is visible. Only set this to true if you have location permission */
    var isEnabled: Boolean
        set(value) {
            map.locationComponent.isLocationComponentEnabled = value
        }
        get() = map.locationComponent.isLocationComponentEnabled

    var isFollowingPosition: Boolean = true
        set(value) {
            field = value
            updateCameraMode()
        }

    var isNavigationMode: Boolean = false
        set(value) {
            field = value
            updateCameraMode()
        }

    init {
        map.locationComponent.activateLocationComponent(LocationComponentActivationOptions.builder(context, mapStyle)
            .locationComponentOptions(LocationComponentOptions.builder(context)
                .foregroundDrawable(R.drawable.location_dot)
                .bearingDrawable(R.drawable.location_direction)
                .bearingTintColor(R.color.location_dot)
                .accuracyColor(R.color.location_dot)
                .accuracyAnimationEnabled(true)
                .build()
            )
            .useDefaultLocationEngine(true)
            .locationEngineRequest(LocationEngineRequest.Builder(1000L).build())
            .build()
        )
        isEnabled = false
        updateCameraMode()
    }

    private fun updateCameraMode() {
        when {
            isFollowingPosition && isNavigationMode ->
                map.locationComponent.setCameraMode(CameraMode.TRACKING_GPS, 450L, 19.0, 0.0, 30.0, null)
            isFollowingPosition ->
                map.locationComponent.setCameraMode(CameraMode.TRACKING, 450L, 19.0, 0.0, 30.0, null)
            else ->
                map.locationComponent.setCameraMode(CameraMode.NONE)
        }
    }
}
