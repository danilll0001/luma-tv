package de.hamid.lumatv.platform;

import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Adapted from TV Detox hdmiInputs/inputLabel. No arbitrary four-input limit. */
public final class HdmiRepository {
    private HdmiRepository() {}

    public static List<TvInputInfo> discover(Context context) {
        TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        if (manager == null) return Collections.emptyList();
        List<TvInputInfo> result = new ArrayList<>();
        for (TvInputInfo input : manager.getTvInputList()) {
            if (input.getType() == TvInputInfo.TYPE_HDMI && input.getParentId() == null
                    && !input.isHidden(context)) result.add(input);
        }
        result.sort(Comparator.comparing(TvInputInfo::getId));
        return result;
    }

    /** True when a device is plugged into this HDMI input (also while the device is in standby). */
    public static boolean connected(Context context, TvInputInfo input) {
        TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        if (manager == null) return false;
        try {
            int state = manager.getInputState(input.getId());
            return state == TvInputManager.INPUT_STATE_CONNECTED
                    || state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Short diagnostic text for the inputs page: raw input state (0 plugged, 1 standby, 2 unplugged) and CEC devices. */
    public static String describe(Context context, TvInputInfo input) {
        TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        if (manager == null) return "";
        int state = -1, children = 0;
        try {
            state = manager.getInputState(input.getId());
            for (TvInputInfo other : manager.getTvInputList()) {
                if (input.getId().equals(other.getParentId())) children++;
            }
        } catch (RuntimeException unavailable) {
            // Keep what we have.
        }
        return "Status " + state + " \u00b7 CEC " + children;
    }

    public static String label(Context context, TvInputInfo input) {
        CharSequence label = input.loadCustomLabel(context);
        if (label == null || label.length() == 0) label = input.loadLabel(context);
        return label == null || label.length() == 0 ? "HDMI" : label.toString();
    }
}
