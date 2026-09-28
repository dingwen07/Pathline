package net.extrawdw.apps.locationhistory.ui

import android.content.Context
import android.view.MotionEvent
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView

/** Keeps map pans and multi-touch gestures from being intercepted by a surrounding scroll view. */
internal class ScrollContainerMapView(context: Context, options: GoogleMapOptions) :
    MapView(context, options) {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // AndroidView forwards this request to Compose's pointer interop, so the map gets moves
        // before verticalScroll can claim them. Claim the initial down, before pan detection.
        parent?.requestDisallowInterceptTouchEvent(true)
        return try {
            super.dispatchTouchEvent(event)
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }
}
