package de.hamid.lumatv.stage;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.format.DateFormat;
import android.widget.EditText;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import de.hamid.lumatv.R;
import de.hamid.lumatv.data.RecentStore;
import de.hamid.lumatv.model.AppEntry;
import de.hamid.lumatv.platform.AppCatalog;
import de.hamid.lumatv.platform.AppLauncher;
import de.hamid.lumatv.platform.HdmiLauncher;
import de.hamid.lumatv.platform.HdmiRepository;
import de.hamid.lumatv.platform.SettingsNavigator;
import de.hamid.lumatv.ui.DashboardActivity;

/**
 * Luma's Home screen since 0.3: a quiet photo Home after CleanFrame (Dock or List), with
 * Settings, All apps and a Home editor. The classic category dashboard stays reachable from
 * Settings → TV &amp; Luma.
 */
public final class StageActivity extends Activity implements StageView.Host {
    private static final long LAUNCH_GUARD_MS = 1200;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private StageView stage;
    private Look look;
    private RecentStore recents;
    private boolean homeVariant;
    private boolean catalogDirty;
    private boolean resumed;
    private long lastLaunch;
    private int catalogToken;
    private Map<String, String> inputCache = new LinkedHashMap<>();
    private TvInputManager tvManager;

    /** Keeps the Inputs control on Home current while a device is plugged in or removed. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { refreshInputs(); }
        @Override public void onInputAdded(String inputId) { refreshInputs(); }
        @Override public void onInputRemoved(String inputId) { refreshInputs(); }
    };

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            main.postDelayed(this, 60000 - now % 60000 + 50);
        }
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (resumed) loadCatalog(); else catalogDirty = true;
        }
    };

    /** Applies the app language chosen in the settings (Deutsch, English, Russian) before anything is drawn. */
    @Override protected void attachBaseContext(Context base) {
        String code = base.getSharedPreferences("luma_home", MODE_PRIVATE).getString("stage_look_language", "system");
        if (code != null && !"system".equals(code)) {
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.setLocale(new Locale(code));
            base = base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }

    private int greetVariant;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        homeVariant = getResources().getBoolean(R.bool.is_home_variant);
        android.content.SharedPreferences prefs = getSharedPreferences("luma_home", MODE_PRIVATE);
        look = new Look(prefs);
        recents = new RecentStore(prefs);
        stage = new StageView(this, this, look);
        setContentView(stage);
        stage.requestFocus();
        if (state == null) stage.onLauncherStart();
        refreshInputs();
        loadCatalog();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packages, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packages, filter);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        greetVariant = new java.util.Random().nextInt(100);
        main.removeCallbacks(clockTick);
        clockTick.run();
        refreshInputs();
        scanPhotos(false);
        try {
            tvManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tvManager != null) tvManager.registerCallback(inputCallback, main);
        } catch (RuntimeException unavailable) {
            tvManager = null;
        }
        if (catalogDirty) loadCatalog();
        stage.setHomeVisible(true);
        stage.requestFocus();
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(clockTick);
        if (tvManager != null) {
            try {
                tvManager.unregisterCallback(inputCallback);
            } catch (RuntimeException unavailable) {
                // Nothing to undo.
            }
            tvManager = null;
        }
        stage.setHomeVisible(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(packages);
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            stage.goHome();
        }
    }

    // ------------------------------------------------------------------ data

    private void updateClock() {
        int mode = look.clockMode();
        boolean h24 = mode == 1 || (mode == 0 && DateFormat.is24HourFormat(this));
        // CleanFrame shows hours and minutes only, without AM/PM or seconds.
        Calendar now = Calendar.getInstance();
        stage.setClock(DateFormat.format(h24 ? "H:mm" : "h:mm", now).toString());
        stage.setGreeting(greetingText(now));
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM");
        stage.setDate(look.dateOn ? new java.text.SimpleDateFormat(pattern, locale).format(now.getTime()) : "");
    }

    /** "Good evening, Name" by time of day (one of several wordings), or a birthday wish; empty when switched off. */
    private String greetingText(Calendar now) {
        if (!look.greetingOn()) return "";
        String lead;
        if (look.birthdaySet() && now.get(Calendar.MONTH) + 1 == look.birthMonth
                && now.get(Calendar.DAY_OF_MONTH) == look.birthDay) {
            lead = getString(R.string.greet_birthday);
        } else {
            int hour = now.get(Calendar.HOUR_OF_DAY);
            int arrayId = hour < 5 ? R.array.greet_night : hour < 11 ? R.array.greet_morning
                    : hour < 17 ? R.array.greet_day : hour < 22 ? R.array.greet_evening : R.array.greet_night;
            String[] options = getResources().getStringArray(arrayId);
            lead = options[greetVariant % options.length];
        }
        String name = look.userName().trim();
        return name.isEmpty() ? lead : lead + ", " + name;
    }

    private void refreshInputs() {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> plugged = new LinkedHashMap<>();
        try {
            for (TvInputInfo input : HdmiRepository.discover(this)) {
                String label = HdmiRepository.label(this, input);
                labels.put(input.getId(), label);
                if (HdmiRepository.connected(this, input)) plugged.put(input.getId(), label);
            }
        } catch (RuntimeException unavailable) {
            // No TV input service: the inputs page says so.
        }
        inputCache = labels;
        stage.setConnectedInputs(plugged);
    }

    private void loadCatalog() {
        catalogDirty = false;
        final int token = ++catalogToken;
        final PackageManager pm = getPackageManager();
        final String own = getPackageName();
        final String ps4 = look.ps4InputId();
        final String ps4Label = getString(R.string.st_ps4_item);
        final Map<String, String> inputLabels = new LinkedHashMap<>(inputCache);
        worker.execute(() -> {
            List<HomeItem> items = new ArrayList<>();
            for (AppEntry entry : AppCatalog.discover(pm, own)) {
                String pkg = entry.packageName();
                String mark = Marks.forPackage(pkg);
                String pickedMark = look.customMark(pkg);
                if (Marks.has(pickedMark)) mark = pickedMark;
                Drawable icon = null;
                if (mark == null) {
                    try {
                        icon = pm.getActivityIcon(entry.component);
                    } catch (PackageManager.NameNotFoundException | RuntimeException gone) {
                        icon = null;
                    }
                }
                String customLabel = look.customLabel(pkg);
                items.add(new HomeItem(pkg, customLabel != null ? customLabel : Marks.label(pkg, entry.label), entry, null, mark, icon));
            }
            boolean ps4Listed = false;
            for (Map.Entry<String, String> input : inputLabels.entrySet()) {
                String id = input.getKey();
                String key = Look.INPUT_PREFIX + id;
                boolean isPs4 = id.equals(ps4);
                ps4Listed |= isPs4;
                String pickedMark = look.customMark(key);
                String mark = Marks.has(pickedMark) ? pickedMark : isPs4 ? "playstation" : "inputs";
                String customLabel = look.customLabel(key);
                String label = customLabel != null ? customLabel : isPs4 ? ps4Label : input.getValue();
                items.add(new HomeItem(key, label, null, id, mark, null));
            }
            if (ps4 != null && !ps4Listed) {
                String key = Look.INPUT_PREFIX + ps4;
                String pickedMark = look.customMark(key);
                String customLabel = look.customLabel(key);
                items.add(new HomeItem(key, customLabel != null ? customLabel : ps4Label, null, ps4,
                        Marks.has(pickedMark) ? pickedMark : "playstation", null));
            }
            items.sort(Comparator.comparing((HomeItem h) -> h.label, String.CASE_INSENSITIVE_ORDER));
            main.post(() -> {
                if (token != catalogToken || isDestroyed()) return;
                stage.setCatalog(items);
            });
        });
    }

    // ------------------------------------------------------------------ Host

    @Override public void launch(HomeItem item) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < LAUNCH_GUARD_MS) return;
        lastLaunch = now;
        boolean ok = item.isInput() ? HdmiLauncher.open(this, item.inputId) : AppLauncher.open(this, item.app, recents);
        if (!ok) {
            lastLaunch = 0;
            toast(item.isInput() ? getString(R.string.input_unavailable) : getString(R.string.app_unavailable, item.label));
        } else if (look.motion) {
            overridePendingTransition(R.anim.launch_in, R.anim.launch_out);
        }
    }

    @Override public void openTvSettings() {
        if (!SettingsNavigator.open(this, Settings.ACTION_SETTINGS)) toast(getString(R.string.settings_unavailable));
    }

    @Override public void openInput(String inputId) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < LAUNCH_GUARD_MS) return;
        lastLaunch = now;
        if (!HdmiLauncher.open(this, inputId)) {
            lastLaunch = 0;
            toast(getString(R.string.input_unavailable));
        }
    }

    @Override public void openClassic() {
        startActivity(new Intent(this, DashboardActivity.class));
    }

    @Override public Map<String, String> inputs() {
        refreshInputs();
        return inputCache;
    }

    @Override public void work(Runnable task) {
        if (!worker.isShutdown()) worker.execute(task);
    }

    @Override public boolean homeVariant() { return homeVariant; }

    @Override public void leave() { finish(); }

    @Override public void catalogChanged() {
        updateClock();
        loadCatalog();
    }

    @Override public void editName() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        input.setText(look.userName());
        input.setSelection(input.getText().length());
        input.setHint(R.string.st_name_hint);
        new AlertDialog.Builder(this, R.style.LumaDialog)
                .setTitle(R.string.st_name_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (look.setUserName(name)) {
                        updateClock();
                        stage.nameChanged();
                    } else {
                        toast(getString(R.string.st_save_failed));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(dialog -> stage.requestFocus())
                .show();
    }

    @Override public void editLabel(String key, String current) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(28)});
        input.setText(current);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this, R.style.LumaDialog)
                .setTitle(R.string.st_rename_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (look.setCustomLabel(key, input.getText().toString().trim())) catalogChanged();
                    else toast(getString(R.string.st_save_failed));
                })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(dialog -> stage.requestFocus())
                .show();
    }

    @Override public void chooseMark(String key) {
        final List<String> ids = new ArrayList<>(Marks.choices());
        final String[] names = new String[ids.size() + 1];
        names[0] = getString(R.string.st_icon_default);
        for (int i = 0; i < ids.size(); i++) names[i + 1] = ids.get(i);
        new AlertDialog.Builder(this, R.style.LumaDialog)
                .setTitle(R.string.st_icon_title)
                .setItems(names, (dialog, which) -> {
                    if (look.setCustomMark(key, which == 0 ? "" : ids.get(which - 1))) catalogChanged();
                    else toast(getString(R.string.st_save_failed));
                })
                .setOnDismissListener(dialog -> stage.requestFocus())
                .show();
    }

    @Override public void searchApps() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.st_search_hint);
        new AlertDialog.Builder(this, R.style.LumaDialog)
                .setTitle(R.string.st_search_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> stage.showSearch(input.getText().toString().trim()))
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(dialog -> stage.requestFocus())
                .show();
    }

    @Override public void pickMediaApp() {
        final List<HomeItem> apps = stage.apps();
        final String[] names = new String[apps.size() + 1];
        names[0] = getString(R.string.st_media_none);
        for (int i = 0; i < apps.size(); i++) names[i + 1] = apps.get(i).label;
        new AlertDialog.Builder(this, R.style.LumaDialog)
                .setTitle(R.string.st_media_title)
                .setItems(names, (dialog, which) -> {
                    if (look.setMediaPackage(which == 0 ? null : apps.get(which - 1).key)) stage.nameChanged();
                    else toast(getString(R.string.st_save_failed));
                })
                .setOnDismissListener(dialog -> stage.requestFocus())
                .show();
    }

    private static final int REQ_PHOTOS = 41;
    private static final String PHOTO_FOLDER = "HorizonHome";

    @Override public void scanPhotos(boolean ask) {
        final String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            if (ask) requestPermissions(new String[]{permission}, REQ_PHOTOS);
            return;
        }
        worker.execute(() -> {
            List<String> found = new ArrayList<>();
            String[] projection = {MediaStore.Images.Media._ID};
            try (Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME + "=?", new String[]{PHOTO_FOLDER},
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                while (cursor != null && cursor.moveToNext() && found.size() < 80) {
                    found.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)).toString());
                }
            } catch (RuntimeException unavailable) {
                // Leave the list empty.
            }
            main.post(() -> {
                if (isDestroyed()) return;
                Walls.setOwn(found);
                stage.photosChanged();
            });
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PHOTOS) scanPhotos(false);
    }

    @Override public List<String> recents() { return recents.read(); }

    @Override public String inputInfo(String inputId) {
        try {
            for (TvInputInfo input : HdmiRepository.discover(this)) {
                if (input.getId().equals(inputId)) return HdmiRepository.describe(this, input);
            }
        } catch (RuntimeException unavailable) {
            // No diagnostics available.
        }
        return "";
    }

    @Override public void confirm(String message, Runnable yes) {
        new AlertDialog.Builder(this, R.style.LumaDialog)
                .setMessage(message)
                .setPositiveButton(R.string.st_remove_short, (dialog, which) -> yes.run())
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(dialog -> stage.requestFocus())
                .show();
    }

    @Override public void editBirthday() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.st_birthday_hint);
        if (look.birthdaySet()) input.setText(String.format(Locale.ROOT, "%02d.%02d.", look.birthDay, look.birthMonth));
        new AlertDialog.Builder(this, R.style.LumaDialog)
                .setTitle(R.string.st_birthday_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("^(\\d{1,2})\\.(\\d{1,2})\\.?$").matcher(input.getText().toString().trim());
                    if (!m.matches()) { toast(getString(R.string.st_birthday_invalid)); return; }
                    int day = Integer.parseInt(m.group(1)), month = Integer.parseInt(m.group(2));
                    if (month < 1 || month > 12 || day < 1 || day > 31) { toast(getString(R.string.st_birthday_invalid)); return; }
                    if (look.setBirthday(month, day)) { updateClock(); stage.nameChanged(); }
                    else toast(getString(R.string.st_save_failed));
                })
                .setNeutralButton(R.string.st_birthday_clear, (dialog, which) -> {
                    if (look.setBirthday(0, 0)) { updateClock(); stage.nameChanged(); }
                })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(dialog -> stage.requestFocus())
                .show();
    }

    @Override public void languageChanged() {
        recreate();
    }

    @Override public void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
