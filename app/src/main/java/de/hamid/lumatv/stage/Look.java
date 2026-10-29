package de.hamid.lumatv.stage;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Committed appearance and the apps on Home, stored locally in the same preferences file as the
 * classic dashboard (so the PS4 input assignment is shared). Every axis is independent, as in
 * CleanFrame: background, layout, app display, control display, font, focus, material, motion.
 */
final class Look {
    static final String BG_PHOTO = "photo", BG_LIGHT = "light", BG_DARK = "dark";
    static final String LAYOUT_DOCK = "dock", LAYOUT_LIST = "list";
    static final String SHOW_BOTH = "both", SHOW_ICONS = "icons", SHOW_TEXT = "text";
    static final String FOCUS_GLASS = "glass", FOCUS_TYPE = "type";
    static final String MAT_SOFT = "soft", MAT_FROST = "frost", MAT_CLEAR = "clear";
    static final String FONT_MANROPE = "manrope", FONT_GEIST = "geist", FONT_NEWSREADER = "newsreader";
    static final String FONT_ROBOTO = "roboto", FONT_CONDENSED = "condensed", FONT_SERIF = "serif";
    static final String ACCENT_WHITE = "white";
    static final String SRC_BUILTIN = "builtin", SRC_OWN = "own", SRC_BOTH = "both";
    static final String IN_AUTO = "auto", IN_ALWAYS = "always", IN_NEVER = "never";
    static final List<String> ACCENTS = Arrays.asList(ACCENT_WHITE, "gold", "coral", "mint", "sky", "lavender");
    static final List<String> SOURCES = Arrays.asList(SRC_BUILTIN, SRC_OWN, SRC_BOTH);
    static final List<String> INPUT_MODES = Arrays.asList(IN_AUTO, IN_ALWAYS, IN_NEVER);
    static final int[] SAVER_CHOICES = {0, 5, 10, 20, 30};
    static final List<String> LANGUAGES = Arrays.asList("system", "de", "en", "ru");
    private static final int[] ACCENT_BRIGHT = {0xFFF3F3EF, 0xFFF4C969, 0xFFFF9E8A, 0xFF8FE3C0, 0xFF8EC9FF, 0xFFC4B0FF};
    private static final int[] ACCENT_DEEP = {0xFF1D1A16, 0xFF8A5A00, 0xFF9C3A28, 0xFF1F6B4E, 0xFF1F5A8F, 0xFF4B3A8F};
    static final String ROT_FIXED = "fixed", ROT_OPEN = "open", ROT_5 = "5", ROT_15 = "15", ROT_60 = "60";

    static final List<String> BGS = Arrays.asList(BG_PHOTO, BG_LIGHT, BG_DARK);
    static final List<String> LAYOUTS = Arrays.asList(LAYOUT_LIST, LAYOUT_DOCK);
    static final List<String> SHOWS = Arrays.asList(SHOW_TEXT, SHOW_ICONS, SHOW_BOTH);
    static final List<String> FOCUSES = Arrays.asList(FOCUS_TYPE, FOCUS_GLASS);
    static final List<String> MATERIALS = Arrays.asList(MAT_SOFT, MAT_FROST, MAT_CLEAR);
    static final List<String> FONTS = Arrays.asList(FONT_MANROPE, FONT_GEIST, FONT_NEWSREADER, FONT_ROBOTO, FONT_CONDENSED, FONT_SERIF);
    static final List<String> ROTATIONS = Arrays.asList(ROT_FIXED, ROT_OPEN, ROT_5, ROT_15, ROT_60);

    /** Home holds one to nine apps, like CleanFrame. */
    static final int HOME_MAX = 9;
    static final String INPUT_PREFIX = "input:";
    static final String FOLDER_PREFIX = "folder:";

    /** Old dashboard key for the PS4 HDMI input; read here so the assignment carries over. */
    private static final String KEY_PS4 = "ps4_input_id_v1";
    private static final String KEY_CLOCK = "clock_mode_v1";
    private static final String KEY_HOME = "stage_home_v1";
    private static final String KEY_NAME = "stage_user_name_v1";
    private static final String KEY_GREETING = "stage_greeting_v1";
    private static final String KEY_LABELS = "stage_labels_v1";
    private static final String KEY_MARKS = "stage_marks_v1";
    private static final String KEY_MEDIA = "stage_media_pkg_v1";
    private static final String KEY_FOLDERS = "stage_photo_folders_v1";
    private static final String KEY_PICKED = "stage_picked_photos_v1";
    private static final String KEY_FOLDER_DATA = "stage_app_folders_v1";
    private static final String P = "stage_look_";

    private static final List<String> DEFAULT_HOME = Arrays.asList(
            "com.stremio.one", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "io.gh.reisxd.tizentube.cobalt", "org.jellyfin.androidtv");

    String bg, layout, apps, controls, focus, material, font, wallpaper, rotation;
    boolean motion;
    /** 0 normal, 1 large, 2 extra large. */
    int clockSize;
    String accent, photoSource, inputsMode, language;
    boolean shuffle, recentFirst, dateOn, fade, glassUi, sound, night, foldersOn, autoUpdate, iconGlass, netIcon;
    /** 0 calm, 1 normal, 2 ultra. */
    int animLevel;
    /** Volume of the interface sounds, 0 to 100. */
    int soundVolume;
    /** Birthday for the greeting; month 0 = not set. */
    int birthMonth, birthDay;
    /** Minutes of inactivity before the photo screensaver starts; 0 = off. */
    int saverMinutes;
    private final SharedPreferences prefs;

    Look(SharedPreferences prefs) {
        this.prefs = prefs;
        bg = BG_PHOTO; // Hell and Dunkel were removed: the photo background is the only one.
        layout = pick("layout", LAYOUTS, LAYOUT_DOCK);
        apps = pick("apps", SHOWS, SHOW_BOTH);
        controls = pick("controls", SHOWS, SHOW_BOTH);
        focus = pick("focus", FOCUSES, FOCUS_GLASS);
        material = pick("material", MATERIALS, MAT_SOFT);
        font = pick("font", FONTS, FONT_MANROPE);
        rotation = pick("rotation", ROTATIONS, ROT_FIXED);
        wallpaper = Walls.normalize(prefs.getString(P + "wallpaper", Walls.DEFAULT_ID));
        motion = prefs.getBoolean(P + "motion", true);
        clockSize = Math.max(0, Math.min(2, prefs.getInt(P + "clock_size", 1)));
        accent = pick("accent", ACCENTS, ACCENT_WHITE);
        photoSource = pick("photo_source", SOURCES, SRC_BUILTIN);
        inputsMode = pick("inputs_mode", INPUT_MODES, IN_AUTO);
        shuffle = prefs.getBoolean(P + "shuffle", false);
        recentFirst = prefs.getBoolean(P + "recent_first", false);
        saverMinutes = prefs.getInt(P + "saver", 10);
        language = pick("language", LANGUAGES, "system");
        dateOn = prefs.getBoolean(P + "date", true);
        fade = prefs.getBoolean(P + "fade", true);
        glassUi = prefs.getBoolean(P + "glass_ui", true);
        sound = prefs.getBoolean(P + "sound", true);
        night = prefs.getBoolean(P + "night", true);
        foldersOn = prefs.getBoolean(P + "folders_on", true);
        autoUpdate = prefs.getBoolean(P + "auto_update", true);
        iconGlass = prefs.getBoolean(P + "icon_glass", true);
        netIcon = prefs.getBoolean(P + "net_icon", true);
        animLevel = Math.max(0, Math.min(2, prefs.getInt(P + "anim_level", 1)));
        soundVolume = Math.max(0, Math.min(100, prefs.getInt(P + "sound_volume", 60)));
        birthMonth = prefs.getInt(P + "birth_month", 0);
        birthDay = prefs.getInt(P + "birth_day", 0);
    }

    private String pick(String key, List<String> allowed, String fallback) {
        String value = prefs.getString(P + key, fallback);
        return allowed.contains(value) ? value : fallback;
    }

    /** Writes one axis. Returns false when storage refused, so the caller keeps the old value. */
    boolean set(String key, String value) {
        boolean ok = prefs.edit().putString(P + key, value).commit();
        if (!ok) return false;
        switch (key) {
            case "bg": bg = value; break;
            case "layout": layout = value; break;
            case "apps": apps = value; break;
            case "controls": controls = value; break;
            case "focus": focus = value; break;
            case "material": material = value; break;
            case "font": font = value; break;
            case "rotation": rotation = value; break;
            case "wallpaper": wallpaper = value; break;
            default: break;
        }
        return true;
    }

    // ------------------------------------------------------------------ Horizon Home extras

    boolean setClockSize(int size) {
        if (!prefs.edit().putInt(P + "clock_size", size).commit()) return false;
        clockSize = size;
        return true;
    }

    boolean setAccent(String value) {
        if (!ACCENTS.contains(value) || !prefs.edit().putString(P + "accent", value).commit()) return false;
        accent = value;
        return true;
    }

    boolean setPhotoSource(String value) {
        if (!SOURCES.contains(value) || !prefs.edit().putString(P + "photo_source", value).commit()) return false;
        photoSource = value;
        return true;
    }

    boolean setInputsMode(String value) {
        if (!INPUT_MODES.contains(value) || !prefs.edit().putString(P + "inputs_mode", value).commit()) return false;
        inputsMode = value;
        return true;
    }

    boolean setShuffle(boolean on) {
        if (!prefs.edit().putBoolean(P + "shuffle", on).commit()) return false;
        shuffle = on;
        return true;
    }

    boolean setRecentFirst(boolean on) {
        if (!prefs.edit().putBoolean(P + "recent_first", on).commit()) return false;
        recentFirst = on;
        return true;
    }

    boolean setSaverMinutes(int minutes) {
        if (!prefs.edit().putInt(P + "saver", minutes).commit()) return false;
        saverMinutes = minutes;
        return true;
    }

    boolean setLanguage(String value) {
        if (!LANGUAGES.contains(value) || !prefs.edit().putString(P + "language", value).commit()) return false;
        language = value;
        return true;
    }

    boolean setDateOn(boolean on) {
        if (!prefs.edit().putBoolean(P + "date", on).commit()) return false;
        dateOn = on;
        return true;
    }

    boolean setNetIcon(boolean on) {
        if (!prefs.edit().putBoolean(P + "net_icon", on).commit()) return false;
        netIcon = on;
        return true;
    }

    boolean setAnimLevel(int level) {
        if (!prefs.edit().putInt(P + "anim_level", level).commit()) return false;
        animLevel = level;
        return true;
    }

    boolean setSoundVolume(int percent) {
        if (!prefs.edit().putInt(P + "sound_volume", percent).commit()) return false;
        soundVolume = percent;
        return true;
    }

    boolean setIconGlass(boolean on) {
        if (!prefs.edit().putBoolean(P + "icon_glass", on).commit()) return false;
        iconGlass = on;
        return true;
    }

    /** Single photos the person ticked (media store content URIs); empty = use the folders. */
    java.util.List<String> pickedPhotos() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_PICKED, "[]"));
            for (int i = 0; i < array.length(); i++) out.add(array.getString(i));
        } catch (JSONException broken) {
            out.clear();
        }
        return out;
    }

    boolean setPickedPhotos(java.util.List<String> uris) {
        JSONArray array = new JSONArray();
        for (String uri : uris) array.put(uri);
        return prefs.edit().putString(KEY_PICKED, array.toString()).commit();
    }

    boolean setFoldersOn(boolean on) {
        if (!prefs.edit().putBoolean(P + "folders_on", on).commit()) return false;
        foldersOn = on;
        return true;
    }

    boolean setAutoUpdate(boolean on) {
        if (!prefs.edit().putBoolean(P + "auto_update", on).commit()) return false;
        autoUpdate = on;
        return true;
    }

    /** Time of the last update check in milliseconds; 0 = never. */
    long lastUpdateCheck() { return prefs.getLong(P + "update_checked", 0L); }

    void setLastUpdateCheck(long millis) { prefs.edit().putLong(P + "update_checked", millis).apply(); }

    // ------------------------------------------------------------------ app folders

    private JSONObject folderData() {
        try {
            return new JSONObject(prefs.getString(KEY_FOLDER_DATA, "{}"));
        } catch (JSONException broken) {
            return new JSONObject();
        }
    }

    java.util.List<String> folderNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.Iterator<String> it = folderData().keys();
        while (it.hasNext()) names.add(it.next());
        java.util.Collections.sort(names);
        return names;
    }

    java.util.List<String> folderMembers(String name) {
        java.util.List<String> out = new java.util.ArrayList<>();
        JSONArray array = folderData().optJSONArray(name);
        if (array != null) for (int i = 0; i < array.length(); i++) out.add(array.optString(i, ""));
        return out;
    }

    /** Creates the folder or replaces its apps. */
    boolean saveFolder(String name, java.util.List<String> members) {
        try {
            JSONObject data = folderData();
            data.put(name, new JSONArray(members));
            return prefs.edit().putString(KEY_FOLDER_DATA, data.toString()).commit();
        } catch (JSONException broken) {
            return false;
        }
    }

    /** Renames the folder and keeps its tile on Home. */
    boolean renameFolder(String from, String to) {
        try {
            JSONObject data = folderData();
            data.put(to, data.opt(from));
            data.remove(from);
            java.util.List<String> keys = homeKeys();
            int at = keys.indexOf(FOLDER_PREFIX + from);
            if (at >= 0) keys.set(at, FOLDER_PREFIX + to);
            return prefs.edit().putString(KEY_FOLDER_DATA, data.toString())
                    .putString(KEY_HOME, new JSONArray(keys).toString()).commit();
        } catch (JSONException broken) {
            return false;
        }
    }

    boolean deleteFolder(String name) {
        JSONObject data = folderData();
        data.remove(name);
        java.util.List<String> keys = homeKeys();
        keys.remove(FOLDER_PREFIX + name);
        return prefs.edit().putString(KEY_FOLDER_DATA, data.toString())
                .putString(KEY_HOME, new JSONArray(keys).toString()).commit();
    }

    boolean setGlassUi(boolean on) {
        if (!prefs.edit().putBoolean(P + "glass_ui", on).commit()) return false;
        glassUi = on;
        return true;
    }

    boolean setSound(boolean on) {
        if (!prefs.edit().putBoolean(P + "sound", on).commit()) return false;
        sound = on;
        return true;
    }

    boolean setNight(boolean on) {
        if (!prefs.edit().putBoolean(P + "night", on).commit()) return false;
        night = on;
        return true;
    }

    /** Photo folders (media store buckets) used for own photos; empty = every folder. */
    java.util.List<String> photoFolders() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_FOLDERS, "[]"));
            for (int i = 0; i < array.length(); i++) out.add(array.getString(i));
        } catch (JSONException broken) {
            out.clear();
        }
        return out;
    }

    boolean setPhotoFolders(java.util.List<String> names) {
        JSONArray array = new JSONArray();
        for (String name : names) array.put(name);
        return prefs.edit().putString(KEY_FOLDERS, array.toString()).commit();
    }

    boolean setFade(boolean on) {
        if (!prefs.edit().putBoolean(P + "fade", on).commit()) return false;
        fade = on;
        return true;
    }

    /** month 0 clears the birthday. */
    boolean setBirthday(int month, int day) {
        if (!prefs.edit().putInt(P + "birth_month", month).putInt(P + "birth_day", day).commit()) return false;
        birthMonth = month;
        birthDay = day;
        return true;
    }

    boolean birthdaySet() { return birthMonth > 0; }

    /** Accent colour for focus plates (settings) and the greeting. */
    int accentColor(boolean lightBackground) {
        int i = Math.max(0, ACCENTS.indexOf(accent));
        return lightBackground ? ACCENT_DEEP[i] : ACCENT_BRIGHT[i];
    }

    boolean accentOn() { return !ACCENT_WHITE.equals(accent); }

    /** Package of the app chosen for USB / network media; null when none. */
    String mediaPackage() {
        String pkg = prefs.getString(KEY_MEDIA, "");
        return pkg == null || pkg.isEmpty() ? null : pkg;
    }

    boolean setMediaPackage(String pkg) {
        return prefs.edit().putString(KEY_MEDIA, pkg == null ? "" : pkg).commit();
    }

    String customLabel(String key) { return customValue(KEY_LABELS, key); }

    String customMark(String key) { return customValue(KEY_MARKS, key); }

    /** An empty value removes the custom label. */
    boolean setCustomLabel(String key, String value) { return setCustomValue(KEY_LABELS, key, value); }

    boolean setCustomMark(String key, String value) { return setCustomValue(KEY_MARKS, key, value); }

    private String customValue(String store, String key) {
        try {
            JSONObject map = new JSONObject(prefs.getString(store, "{}"));
            String value = map.optString(key, "");
            return value.isEmpty() ? null : value;
        } catch (JSONException broken) {
            return null;
        }
    }

    private boolean setCustomValue(String store, String key, String value) {
        try {
            JSONObject map = new JSONObject(prefs.getString(store, "{}"));
            if (value == null || value.isEmpty()) map.remove(key); else map.put(key, value);
            return prefs.edit().putString(store, map.toString()).commit();
        } catch (JSONException broken) {
            return false;
        }
    }

    boolean setMotion(boolean on) {
        if (!prefs.edit().putBoolean(P + "motion", on).commit()) return false;
        motion = on;
        return true;
    }

    /** Material applies to a photographic Dock, or Glass focus on a photographic List. */
    boolean materialApplies() {
        return BG_PHOTO.equals(bg) && (LAYOUT_DOCK.equals(layout) || FOCUS_GLASS.equals(focus));
    }

    boolean photo() { return BG_PHOTO.equals(bg); }
    boolean dock() { return LAYOUT_DOCK.equals(layout); }
    boolean glass() { return FOCUS_GLASS.equals(focus); }

    // ------------------------------------------------------------------ Home apps

    List<String> homeKeys() {
        String raw = prefs.getString(KEY_HOME, null);
        List<String> keys = new ArrayList<>();
        if (raw == null) {
            keys.addAll(DEFAULT_HOME);
            String ps4 = ps4InputId();
            if (ps4 != null) keys.add(INPUT_PREFIX + ps4);
            return keys;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && keys.size() < HOME_MAX; i++) {
                String key = array.optString(i, "");
                if (!key.isEmpty() && !keys.contains(key)) keys.add(key);
            }
        } catch (org.json.JSONException broken) {
            keys.addAll(DEFAULT_HOME);
        }
        return keys;
    }

    boolean saveHomeKeys(List<String> keys) {
        return prefs.edit().putString(KEY_HOME, new JSONArray(keys).toString()).commit();
    }

    String ps4InputId() { return prefs.getString(KEY_PS4, null); }

    boolean setPs4InputId(String inputId) {
        SharedPreferences.Editor edit = prefs.edit();
        if (inputId == null) edit.remove(KEY_PS4); else edit.putString(KEY_PS4, inputId);
        return edit.commit();
    }

    /** 0 = system, 1 = 24 h, 2 = 12 h; shared with the classic dashboard. */
    int clockMode() {
        int mode = prefs.getInt(KEY_CLOCK, 0);
        return mode == 1 || mode == 2 ? mode : 0;
    }

    boolean setClockMode(int mode) { return prefs.edit().putInt(KEY_CLOCK, mode).commit(); }

    // ------------------------------------------------------------------ greeting

    /** Name shown in the greeting. Empty means the greeting is shown without a name. */
    String userName() { return prefs.getString(KEY_NAME, ""); }

    boolean setUserName(String name) {
        return prefs.edit().putString(KEY_NAME, name == null ? "" : name).commit();
    }

    /** The greeting under the clock is on by default. */
    boolean greetingOn() { return prefs.getBoolean(KEY_GREETING, true); }

    boolean setGreetingOn(boolean on) { return prefs.edit().putBoolean(KEY_GREETING, on).commit(); }

    static List<String> unmodifiable(List<String> list) { return Collections.unmodifiableList(list); }
}
