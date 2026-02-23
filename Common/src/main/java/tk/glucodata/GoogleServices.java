package tk.glucodata;

import android.content.Context;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailabilityLight;

public final class GoogleServices {
    private static final String LOG_ID = "GoogleServices";

    private GoogleServices() {
    }

    public static boolean isPlayServicesAvailable(Context context) {
        if (context == null) {
            return false;
        }
        try {
            Context appContext = context.getApplicationContext();
            int status = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(appContext);
            boolean available = status == ConnectionResult.SUCCESS;
            if (!available) {
                Log.w(LOG_ID, "Google Play Services unavailable, status=" + status);
            }
            return available;
        } catch (Throwable th) {
            Log.stack(LOG_ID, "isPlayServicesAvailable", th);
            return false;
        }
    }
}
