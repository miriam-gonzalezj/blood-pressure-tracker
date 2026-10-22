package com.mtagj.bptracker.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

/**
 * Best-effort altitude tagging via GPS, for the "altitude -if possible-"
 * request.
 *
 * IMPORTANT ACCURACY CAVEAT (worth knowing before relying on this):
 * phone GPS altitude is typically only accurate to +/- 10-30 meters (it's
 * the least accurate of the three GPS coordinates), and can be off by much
 * more indoors or in cities. It also needs a recent GPS/network fix, so the
 * very first call after opening the app may return nothing.
 *
 * This is why the app never blocks saving on it, and the altitude field is
 * always a plain, editable text field: this just pre-fills a best guess in
 * the same "NNNN m snm" format already used in the spreadsheet (e.g. a
 * "1500 m snm" style value).
 *
 * A future upgrade path that is NOT implemented here: reverse the GPS
 * lat/lon through an elevation lookup service (e.g. Open-Elevation) for a
 * far more accurate figure, at the cost of needing an internet connection
 * at save time. Worth doing later if GPS altitude proves too noisy in
 * practice.
 */
public class AltitudeHelper {

    public interface Listener {
        /** altitudeLabel is e.g. "1500 m snm"; null if no fix could be obtained. */
        void onResult(String altitudeLabel);
    }

    public static boolean hasLocationPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void fetchAltitude(Context context, Listener listener) {
        if (!hasLocationPermission(context)) {
            listener.onResult(null);
            return;
        }

        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(context);
        CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(2 * 60 * 1000) // accept a fix up to 2 minutes old
                .build();

        try {
            client.getCurrentLocation(request, new CancellationTokenSource().getToken())
                    .addOnSuccessListener(location -> listener.onResult(format(location)))
                    .addOnFailureListener(e -> listener.onResult(null));
        } catch (SecurityException e) {
            // Permission was revoked between the check above and this call — fail quietly.
            listener.onResult(null);
        }
    }

    private static String format(Location location) {
        if (location == null || !location.hasAltitude()) return null;
        long meters = Math.round(location.getAltitude());
        return meters + " m snm";
    }
}
