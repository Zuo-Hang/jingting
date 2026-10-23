package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/** How long Home sits untouched before it clears itself down to the clock and the wallpaper. */
final class IdleClear {
    static final String KEY_MINUTES = "idle_clear_minutes";
    /** Off, the two-minute minimum, then every five minutes through half an hour. */
    static final int[] MINUTES = {0, 2, 5, 10, 15, 20, 25, 30};

    private IdleClear() {}

    static int minutes(SharedPreferences prefs) {
        return clamp(prefs.getInt(KEY_MINUTES, 0));
    }

    static void save(Context context, int minutes) {
        context.getSharedPreferences(LocalSlideshow.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MINUTES, clamp(minutes)).apply();
    }

    private static int clamp(int minutes) {
        for (int allowed : MINUTES) if (allowed == minutes) return minutes;
        return 0;
    }
}
