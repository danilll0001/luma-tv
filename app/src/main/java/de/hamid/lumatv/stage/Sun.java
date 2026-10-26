package de.hamid.lumatv.stage;

import java.util.Calendar;
import java.util.TimeZone;

/** Offline sunrise and sunset (NOAA approximation); no network, no location permission. */
final class Sun {
    private Sun() {}

    /** True between sunset and sunrise at the given place. */
    static boolean isNight(long millis, double lat, double lon) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(millis);
        int day = utc.get(Calendar.DAY_OF_YEAR);
        double g = 2 * Math.PI / 365 * (day - 1 + (utc.get(Calendar.HOUR_OF_DAY) - 12) / 24.0);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g)
                - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
                - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double latRad = Math.toRadians(lat);
        double cosHa = Math.cos(Math.toRadians(90.833)) / (Math.cos(latRad) * Math.cos(decl))
                - Math.tan(latRad) * Math.tan(decl);
        if (cosHa > 1) return true;
        if (cosHa < -1) return false;
        double ha = Math.toDegrees(Math.acos(cosHa));
        double sunrise = 720 - 4 * (lon + ha) - eqTime;
        double sunset = 720 - 4 * (lon - ha) - eqTime;
        double now = utc.get(Calendar.HOUR_OF_DAY) * 60 + utc.get(Calendar.MINUTE);
        return now < sunrise || now > sunset;
    }
}
