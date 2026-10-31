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
    private final Map<String, Integer> inputStates = new java.util.HashMap<>();
    private boolean devicesRegistered;

    /** Keeps the Inputs control on Home current while a device is plugged in or removed. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) {
            Integer previous = inputStates.put(inputId, state);
            main.removeCallbacks(inputRefresh);
            main.postDelayed(inputRefresh, 500);
            boolean plugged = state == TvInputManager.INPUT_STATE_CONNECTED
                    || state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY;
            boolean wasPlugged = previous != null && (previous == TvInputManager.INPUT_STATE_CONNECTED
                    || previous == TvInputManager.INPUT_STATE_CONNECTED_STANDBY);
            if (plugged && previous != null && !wasPlugged && look.popups) {
                String label = inputCache.get(inputId);
                final String id = inputId;
                notifyDevice("inputs", label != null ? getString(R.string.st_pop_input, label) : getString(R.string.st_pop_hdmi),
                        () -> openInput(id));
            }
        }
        @Override public void onInputAdded(String inputId) { main.removeCallbacks(inputRefresh); main.postDelayed(inputRefresh, 500); }
        @Override public void onInputRemoved(String inputId) { main.removeCallbacks(inputRefresh); main.postDelayed(inputRefresh, 500); }
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
            main.removeCallbacks(packageReload);
            main.postDelayed(packageReload, 1500);
        }
    };

    private final Runnable packageReload = () -> {
        if (resumed) loadCatalog(); else catalogDirty = true;
    };

    private final Runnable inputRefresh = this::refreshInputs;

    /** Applies the app language chosen in the settings (Deutsch, English, Russian) before anything is drawn. */
    @Override protected void attachBaseContext(Context base) {
        String code = base.getSharedPreferences("luma_home", MODE_PRIVATE).getString("stage_look_language", "system");
        appliedLanguage = code == null ? "system" : code;
        if (code != null && !"system".equals(code)) {
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.setLocale(new Locale(code));
            base = base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }

    private int greetVariant;
    private String appliedLanguage = "system";
    private String pendingWallpaper;

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
        // The language may have been changed in the classic view meanwhile.
        String savedLanguage = getSharedPreferences("luma_home", MODE_PRIVATE).getString("stage_look_language", "system");
        if (savedLanguage != null && !savedLanguage.equals(appliedLanguage)) {
            recreate();
            return;
        }
        resumed = true;
        greetVariant = new java.util.Random().nextInt(100);
        main.removeCallbacks(clockTick);
        clockTick.run();
        refreshInputs();
        scanPhotos(false);
        try {
            connectivity = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity != null && !netRegistered) {
                connectivity.registerDefaultNetworkCallback(netCallback);
                netRegistered = true;
            }
        } catch (RuntimeException unavailable) {
            netRegistered = false;
        }
        updateNetwork();
        if (!devicesRegistered) {
            android.content.IntentFilter media = new android.content.IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
            media.addDataScheme("file");
            registerReceiver(deviceReceiver, media);
            registerReceiver(deviceReceiver, new android.content.IntentFilter("android.hardware.usb.action.USB_DEVICE_ATTACHED"));
            registerReceiver(deviceReceiver, new android.content.IntentFilter("android.bluetooth.device.action.ACL_CONNECTED"));
            devicesRegistered = true;
        }
        registerStorageNotice();
        android.content.SharedPreferences shared = getSharedPreferences("luma_home", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 31 && look.popups && !shared.getBoolean("stage_bt_asked", false)
                && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            shared.edit().putBoolean("stage_bt_asked", true).commit();
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, 43);
        }
        if (!installRegistered) {
            android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_INSTALL);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(installResult, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(installResult, filter);
            installRegistered = true;
        }
        if (look.autoUpdate && System.currentTimeMillis() - look.lastUpdateCheck() > 24L * 60 * 60 * 1000) checkUpdate(false);
        try {
            tvManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tvManager != null) tvManager.registerCallback(inputCallback, main);
        } catch (RuntimeException unavailable) {
            tvManager = null;
        }
        if (catalogDirty) loadCatalog();
        stage.setHomeVisible(true);
        stage.requestFocus();
        if ("classic".equals(getSharedPreferences("luma_home", MODE_PRIVATE).getString("stage_look_ui_mode", "new"))) {
            startActivity(new Intent(this, DashboardActivity.class));
            overridePendingTransition(0, 0);
        }
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(clockTick);
        if (connectivity != null && netRegistered) {
            try {
                connectivity.unregisterNetworkCallback(netCallback);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            netRegistered = false;
        }
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
        if (installRegistered) {
            try { unregisterReceiver(installResult); } catch (RuntimeException ignored) { }
            installRegistered = false;
        }
        if (devicesRegistered) {
            try { unregisterReceiver(deviceReceiver); } catch (RuntimeException ignored) { }
            devicesRegistered = false;
        }
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
                inputStates.putIfAbsent(input.getId(), HdmiRepository.state(this, input));
                if (HdmiRepository.connected(this, input)) plugged.put(input.getId(), label);
            }
        } catch (RuntimeException unavailable) {
            // No TV input service: the inputs page says so.
        }
        inputCache = labels;
        stage.setConnectedInputs(plugged);
        autoAddInputs();
    }

    /** Puts every HDMI input the TV reports on Home once; removed ones stay removed. */
    /** Once: HDMI inputs belong in All apps, so tiles the launcher added to Home earlier are taken off again. */
    private void cleanAutoInputs() {
        android.content.SharedPreferences shared = getSharedPreferences("luma_home", MODE_PRIVATE);
        if (shared.getBoolean("stage_inputs_clean_v1", false)) return;
        shared.edit().putBoolean("stage_inputs_clean_v1", true).commit();
        List<String> seen = look.seenInputs();
        if (seen.isEmpty()) return;
        List<String> keys = look.homeKeys();
        boolean changed = false;
        for (String id : seen) changed |= keys.remove(Look.INPUT_PREFIX + id);
        if (changed && look.saveHomeKeys(keys)) {
            if (resumed) loadCatalog(); else catalogDirty = true;
        }
    }

    private void autoAddInputs() {
        cleanAutoInputs();
        if (!look.autoInputs || inputCache.isEmpty()) return;
        List<String> seen = look.seenInputs();
        List<String> keys = look.homeKeys();
        boolean added = false, fresh = false;
        for (String id : inputCache.keySet()) {
            if (seen.contains(id)) continue;
            seen.add(id);
            fresh = true;
            String key = Look.INPUT_PREFIX + id;
            if (!keys.contains(key) && keys.size() < Look.HOME_MAX) {
                keys.add(key);
                added = true;
            }
        }
        if (fresh) look.setSeenInputs(seen);
        if (added && look.saveHomeKeys(keys)) {
            if (resumed) loadCatalog(); else catalogDirty = true;
        }
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
                if (mark == null || (look.colorIcons && !Marks.has(pickedMark))) {
                    try {
                        icon = pm.getActivityIcon(entry.component);
                    } catch (PackageManager.NameNotFoundException | RuntimeException gone) {
                        icon = null;
                    }
                }
                String customLabel = look.customLabel(pkg);
                HomeItem item = new HomeItem(pkg, customLabel != null ? customLabel : Marks.label(pkg, entry.label), entry, null, mark, icon);
                if (mark == null && icon != null) item.mono = Marks.monoLogo(icon);
                items.add(item);
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
            if (look.foldersOn) {
                for (String folder : look.folderNames()) {
                    String key = Look.FOLDER_PREFIX + folder;
                    String pickedMark = look.customMark(key);
                    items.add(new HomeItem(key, folder, null, null, Marks.has(pickedMark) ? pickedMark : "folder", null));
                }
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

    /** Switches to the classic view and keeps it as the Home view until the person switches back. */
    @Override public void openClassic() {
        getSharedPreferences("luma_home", MODE_PRIVATE).edit().putString("stage_look_ui_mode", "classic").commit();
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
        final String permission = photoPermission();
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            if (ask) requestPermissions(new String[]{permission}, REQ_PHOTOS);
            return;
        }
        final List<String> folders = look.photoFolders();
        final List<String> picked = look.pickedPhotos();
        worker.execute(() -> {
            List<String> found = new ArrayList<>();
            StringBuilder where = new StringBuilder(MediaStore.Images.Media.WIDTH + ">=1000 AND "
                    + MediaStore.Images.Media.HEIGHT + ">=600");
            String[] args = null;
            if (!folders.isEmpty()) {
                where.append(" AND ").append(MediaStore.Images.Media.BUCKET_DISPLAY_NAME).append(" IN (");
                for (int i = 0; i < folders.size(); i++) where.append(i == 0 ? "?" : ",?");
                where.append(")");
                args = folders.toArray(new String[0]);
            }
            try (Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID}, where.toString(), args,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                while (cursor != null && cursor.moveToNext() && found.size() < 300) {
                    found.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)).toString());
                }
            } catch (RuntimeException unavailable) {
                // Leave the list empty.
            }
            main.post(() -> {
                if (isDestroyed()) return;
                Walls.setOwn(picked.isEmpty() ? found : picked);
                if (pendingWallpaper != null) {
                    stage.showWallpaper(pendingWallpaper);
                    pendingWallpaper = null;
                }
                stage.photosChanged();
            });
        });
    }

    private List<String> queryPhotos(List<String> folders, int limit) {
        List<String> found = new ArrayList<>();
        StringBuilder where = new StringBuilder(MediaStore.Images.Media.WIDTH + ">=1000 AND "
                + MediaStore.Images.Media.HEIGHT + ">=600");
        String[] args = null;
        if (!folders.isEmpty()) {
            where.append(" AND ").append(MediaStore.Images.Media.BUCKET_DISPLAY_NAME).append(" IN (");
            for (int i = 0; i < folders.size(); i++) where.append(i == 0 ? "?" : ",?");
            where.append(")");
            args = folders.toArray(new String[0]);
        }
        try (Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, where.toString(), args,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            while (cursor != null && cursor.moveToNext() && found.size() < limit) {
                found.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)).toString());
            }
        } catch (RuntimeException unavailable) {
            // Return what we have.
        }
        return found;
    }

    @Override public void pickPhotos() {
        final String permission = photoPermission();
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, REQ_PHOTOS);
            return;
        }
        final List<String> folders = look.photoFolders();
        worker.execute(() -> {
            final List<String> uris = queryPhotos(folders, 160);
            main.post(() -> showPhotoDialog(uris));
        });
    }

    private android.graphics.Bitmap thumb(String uri, int targetWidth) {
        try {
            android.content.ContentResolver resolver = getContentResolver();
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = resolver.openInputStream(android.net.Uri.parse(uri))) {
                android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            try (java.io.InputStream in = resolver.openInputStream(android.net.Uri.parse(uri))) {
                return android.graphics.BitmapFactory.decodeStream(in, null, options);
            }
        } catch (java.io.IOException | RuntimeException | OutOfMemoryError failed) {
            return null;
        }
    }

    /** A grid of photos; OK ticks or unticks one. Several can be chosen. */
    private void showPhotoDialog(final List<String> candidates) {
        if (isDestroyed()) return;
        final java.util.Set<String> chosen = new java.util.LinkedHashSet<>(look.pickedPhotos());
        for (String uri : chosen) if (!candidates.contains(uri)) candidates.add(0, uri);
        if (candidates.isEmpty()) {
            toast(getString(R.string.st_own_none));
            return;
        }
        final android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        final int cellHeight = Math.round(metrics.widthPixels * .115f);
        final int badge = Math.round(34 * metrics.density), gap = Math.round(8 * metrics.density);
        final android.util.LruCache<String, android.graphics.Bitmap> cache = new android.util.LruCache<>(80);
        final java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newFixedThreadPool(3);
        final android.widget.GridView grid = new android.widget.GridView(this);
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0x66FFFFFF));
        grid.setDrawSelectorOnTop(true);
        final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return candidates.size(); }
            @Override public Object getItem(int position) { return candidates.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override public android.view.View getView(int position, android.view.View convert, android.view.ViewGroup parent) {
                android.widget.FrameLayout cell;
                final android.widget.ImageView image;
                android.widget.TextView mark;
                if (convert instanceof android.widget.FrameLayout) {
                    cell = (android.widget.FrameLayout) convert;
                    image = (android.widget.ImageView) cell.getChildAt(0);
                    mark = (android.widget.TextView) cell.getChildAt(1);
                } else {
                    cell = new android.widget.FrameLayout(StageActivity.this);
                    cell.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, cellHeight));
                    image = new android.widget.ImageView(StageActivity.this);
                    image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                    image.setBackgroundColor(0xFF2A2C31);
                    cell.addView(image, new android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                    mark = new android.widget.TextView(StageActivity.this);
                    mark.setText("\u2713");
                    mark.setTextColor(0xFFFFFFFF);
                    mark.setTextSize(18);
                    mark.setGravity(android.view.Gravity.CENTER);
                    mark.setBackgroundColor(0xE6208A4A);
                    android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                            badge, badge, android.view.Gravity.TOP | android.view.Gravity.END);
                    lp.setMargins(0, gap / 2, gap / 2, 0);
                    cell.addView(mark, lp);
                }
                final String uri = candidates.get(position);
                mark.setVisibility(chosen.contains(uri) ? android.view.View.VISIBLE : android.view.View.GONE);
                image.setTag(uri);
                android.graphics.Bitmap cached = cache.get(uri);
                if (cached != null) {
                    image.setImageBitmap(cached);
                } else {
                    image.setImageDrawable(null);
                    loader.execute(() -> {
                        final android.graphics.Bitmap bitmap = thumb(uri, 360);
                        if (bitmap == null) return;
                        cache.put(uri, bitmap);
                        runOnUiThread(() -> {
                            if (uri.equals(image.getTag())) image.setImageBitmap(bitmap);
                        });
                    });
                }
                return cell;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            String uri = candidates.get(position);
            if (!chosen.remove(uri)) chosen.add(uri);
            adapter.notifyDataSetChanged();
        });
        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.LumaDialog)
                .setTitle(R.string.st_photos_title)
                .setView(grid)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    List<String> picked = new ArrayList<>(chosen);
                    if (picked.size() > 100) picked = new ArrayList<>(picked.subList(0, 100));
                    if (!look.setPickedPhotos(picked)) {
                        toast(getString(R.string.st_save_failed));
                        return;
                    }
                    if (!picked.isEmpty() && Look.SRC_BUILTIN.equals(look.photoSource)) look.setPhotoSource(Look.SRC_OWN);
                    pendingWallpaper = picked.size() == 1 ? "usr:" + picked.get(0) : null;
                    scanPhotos(false);
                })
                .setNeutralButton(R.string.st_folders, (d, which) -> pickFolders())
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> {
            loader.shutdownNow();
            stage.requestFocus();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Math.round(metrics.widthPixels * .82f), Math.round(metrics.heightPixels * .84f));
        }
    }

    private String photoPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    @Override public void pickFolders() {
        final String permission = photoPermission();
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, REQ_PHOTOS);
            return;
        }
        worker.execute(() -> {
            Map<String, Integer> counts = new java.util.TreeMap<>();
            try (Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media.BUCKET_DISPLAY_NAME},
                    MediaStore.Images.Media.WIDTH + ">=1000 AND " + MediaStore.Images.Media.HEIGHT + ">=600", null, null)) {
                while (cursor != null && cursor.moveToNext()) {
                    String name = cursor.getString(0);
                    if (name == null) continue;
                    Integer old = counts.get(name);
                    counts.put(name, old == null ? 1 : old + 1);
                }
            } catch (RuntimeException unavailable) {
                // Show the empty message below.
            }
            main.post(() -> showFolderDialog(counts));
        });
    }

    private void showFolderDialog(Map<String, Integer> counts) {
        if (isDestroyed()) return;
        if (counts.isEmpty()) {
            toast(getString(R.string.st_own_none));
            return;
        }
        final List<String> names = new ArrayList<>(counts.keySet());
        final String[] labels = new String[names.size()];
        final boolean[] checked = new boolean[names.size()];
        List<String> current = look.photoFolders();
        for (int i = 0; i < names.size(); i++) {
            labels[i] = names.get(i) + " (" + counts.get(names.get(i)) + ")";
            checked[i] = current.contains(names.get(i));
        }
        new AlertDialog.Builder(this, R.style.LumaDialog)
                .setTitle(R.string.st_folders)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < names.size(); i++) if (checked[i]) chosen.add(names.get(i));
                    if (look.setPhotoFolders(chosen)) scanPhotos(false);
                    else toast(getString(R.string.st_save_failed));
                })
                .setNeutralButton(R.string.st_folders_all, (dialog, which) -> {
                    if (look.setPhotoFolders(new ArrayList<String>())) scanPhotos(false);
                })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(dialog -> stage.requestFocus())
                .show();
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

    // ------------------------------------------------------------------ network, weather, system settings

    private android.net.ConnectivityManager connectivity;
    private boolean netRegistered;
    private String weatherText = "";
    private long weatherAt;

    private final android.net.ConnectivityManager.NetworkCallback netCallback = new android.net.ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(android.net.Network network) { main.post(StageActivity.this::updateNetwork); }

        @Override public void onLost(android.net.Network network) { main.post(StageActivity.this::updateNetwork); }

        @Override public void onCapabilitiesChanged(android.net.Network network, android.net.NetworkCapabilities caps) {
            main.post(StageActivity.this::updateNetwork);
        }
    };

    private void updateNetwork() {
        int kind = 0;
        boolean online = false;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network active = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            if (caps != null) {
                kind = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ? 2
                        : caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? 1 : 3;
                online = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
        } catch (RuntimeException unavailable) {
            kind = 0;
        }
        stage.setNetwork(kind, online);
    }

    private void openSettingsScreen(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException missing) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException none) {
                toast(getString(R.string.settings_unavailable));
            }
        }
    }

    private final android.content.BroadcastReceiver deviceReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!look.popups || intent.getAction() == null) return;
            if ("android.bluetooth.device.action.ACL_CONNECTED".equals(intent.getAction())) {
                notifyDevice("bluetooth", getString(R.string.st_pop_bt), StageActivity.this::openBluetoothSettings);
            } else {
                notifyDevice("usb", getString(R.string.st_pop_usb), () -> stage.openMedia());
            }
        }
    };

    private final Map<String, Long> lastNotice = new java.util.HashMap<>();
    private Object storageCallback;

    /** One card per device kind and 8 seconds; the system sends bursts of broadcasts when something is plugged in. */
    private void notifyDevice(String glyph, String title, Runnable action) {
        if (!look.popups) return;
        long now = android.os.SystemClock.uptimeMillis();
        Long last = lastNotice.get(glyph);
        if (last != null && now - last < 8000) return;
        lastNotice.put(glyph, now);
        stage.showPopup(glyph, title, getString(R.string.st_pop_hint), action);
    }

    /** Android 11 and newer report plugged-in storage reliably through the storage manager. */
    private void registerStorageNotice() {
        if (Build.VERSION.SDK_INT < 30 || storageCallback != null) return;
        try {
            final android.os.storage.StorageManager manager =
                    (android.os.storage.StorageManager) getSystemService(Context.STORAGE_SERVICE);
            if (manager == null) return;
            android.os.storage.StorageManager.StorageVolumeCallback callback = new android.os.storage.StorageManager.StorageVolumeCallback() {
                @Override public void onStateChanged(android.os.storage.StorageVolume volume) {
                    if (volume.isRemovable() && android.os.Environment.MEDIA_MOUNTED.equals(volume.getState())) {
                        String name = volume.getDescription(StageActivity.this);
                        notifyDevice("usb", name == null || name.isEmpty() ? getString(R.string.st_pop_usb)
                                : getString(R.string.st_pop_input, name), () -> stage.openMedia());
                    }
                }
            };
            manager.registerStorageVolumeCallback(getMainExecutor(), callback);
            storageCallback = callback;
        } catch (RuntimeException unavailable) {
            storageCallback = null;
        }
    }

    @Override public void openNetworkSettings() { openSettingsScreen(Settings.ACTION_WIFI_SETTINGS); }

    @Override public void openBluetoothSettings() { openSettingsScreen(Settings.ACTION_BLUETOOTH_SETTINGS); }

    @Override public String versionText() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception unknown) {
            return "";
        }
    }

    @Override public String weatherText() { return weatherText; }

    private int weatherWord(int code) {
        if (code == 0) return R.string.st_wx_clear;
        if (code <= 3) return R.string.st_wx_cloudy;
        if (code == 45 || code == 48) return R.string.st_wx_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.string.st_wx_snow;
        if (code >= 95) return R.string.st_wx_storm;
        return R.string.st_wx_rain;
    }

    /** Current weather for Reutlingen from open-meteo.com (no key, only temperature and weather code). */
    @Override public void refreshWeather() {
        long now = System.currentTimeMillis();
        if (!weatherText.isEmpty() && now - weatherAt < 15 * 60 * 1000L) return;
        weatherAt = now;
        worker.execute(() -> {
            String text = "";
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                        "https://api.open-meteo.com/v1/forecast?latitude=48.49&longitude=9.21&current=temperature_2m,weather_code")
                        .openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    org.json.JSONObject current = new org.json.JSONObject(readAll(c.getInputStream())).getJSONObject("current");
                    long temp = Math.round(current.getDouble("temperature_2m"));
                    text = temp + " \u00b0C \u00b7 " + getString(weatherWord(current.getInt("weather_code")));
                }
            } catch (java.io.IOException | org.json.JSONException | RuntimeException offline) {
                text = "";
            }
            final String result = text;
            main.post(() -> {
                if (isDestroyed()) return;
                if (result.isEmpty()) weatherAt = 0; else weatherText = result;
                stage.nameChanged();
            });
        });
    }

    // ------------------------------------------------------------------ dialogs, updates, backup

    private static final String REPO = "danilll0001/luma-tv";
    private static final String ACTION_INSTALL = "de.horizonhome.tv.INSTALL_RESULT";
    private static final int REQ_IMPORT = 42;
    private String updateUrl, updateText = "";
    private boolean installRegistered;

    private final android.content.BroadcastReceiver installResult = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS,
                    android.content.pm.PackageInstaller.STATUS_FAILURE);
            if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(confirm);
                }
            } else if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                toast(getString(R.string.st_update_failed));
            }
        }
    };

    @Override public void promptText(String title, String initial, java.util.function.Consumer<String> result) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        input.setText(initial);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this, R.style.LumaDialog)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> result.accept(input.getText().toString().trim()))
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(dialog -> stage.requestFocus())
                .show();
    }

    @Override public void chooseApps(String title, List<HomeItem> apps, List<String> checkedKeys,
                                     java.util.function.Consumer<List<String>> result) {
        final List<HomeItem> sorted = new ArrayList<>(apps);
        java.util.Collections.sort(sorted, (a, b) -> a.label.compareToIgnoreCase(b.label));
        final String[] names = new String[sorted.size()];
        final boolean[] checked = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            names[i] = sorted.get(i).label;
            checked[i] = checkedKeys.contains(sorted.get(i).key);
        }
        new AlertDialog.Builder(this, R.style.LumaDialog)
                .setTitle(title)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<String> keys = new ArrayList<>();
                    for (int i = 0; i < sorted.size(); i++) if (checked[i]) keys.add(sorted.get(i).key);
                    result.accept(keys);
                })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(dialog -> stage.requestFocus())
                .show();
    }

    private static int[] versionParts(String version) {
        int[] out = new int[3];
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
                .matcher(version == null ? "" : version);
        if (m.find()) {
            out[0] = Integer.parseInt(m.group(1));
            out[1] = Integer.parseInt(m.group(2));
            out[2] = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        }
        return out;
    }

    private static boolean isNewer(String tag, String current) {
        int[] a = versionParts(tag), b = versionParts(current);
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }

    private static String readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }

    @Override public String updateStatus() {
        return updateText.isEmpty() ? getString(R.string.st_update_idle) : updateText;
    }

    @Override public boolean updateReady() { return updateUrl != null; }

    @Override public void checkUpdate(final boolean manual) {
        updateText = getString(R.string.st_update_checking);
        stage.nameChanged();
        worker.execute(() -> {
            String tag = null, url = null;
            int problem = 0; // 0 fine, 1 no release, 2 offline
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "HorizonHome");
                int code = c.getResponseCode();
                if (code == 404) {
                    problem = 1;
                } else if (code != 200) {
                    problem = 2;
                } else {
                    org.json.JSONObject json = new org.json.JSONObject(readAll(c.getInputStream()));
                    tag = json.optString("tag_name");
                    boolean preview = getPackageName().endsWith(".preview");
                    org.json.JSONArray assets = json.optJSONArray("assets");
                    for (int i = 0; assets != null && i < assets.length(); i++) {
                        org.json.JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name");
                        if (name.endsWith(".apk") && name.contains(preview ? "preview" : "home")) {
                            url = asset.optString("browser_download_url");
                            break;
                        }
                    }
                }
            } catch (java.io.IOException | org.json.JSONException | RuntimeException offline) {
                problem = 2;
            }
            final String foundTag = tag, foundUrl = url;
            final int failed = problem;
            main.post(() -> {
                if (isDestroyed()) return;
                String current = "";
                try {
                    current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (Exception unknown) {
                    // Keep an empty version: every release counts as newer.
                }
                look.setLastUpdateCheck(System.currentTimeMillis());
                if (failed == 1) {
                    updateText = getString(R.string.st_update_none);
                    updateUrl = null;
                } else if (failed == 2) {
                    updateText = getString(R.string.st_update_offline);
                    updateUrl = null;
                } else if (foundUrl != null && !foundUrl.isEmpty() && isNewer(foundTag, current)) {
                    updateText = getString(R.string.st_update_available, foundTag);
                    updateUrl = foundUrl;
                    if (!manual) toast(updateText);
                } else {
                    updateText = getString(R.string.st_update_current, current);
                    updateUrl = null;
                }
                stage.nameChanged();
            });
        });
    }

    @Override public void installUpdate() {
        final String url = updateUrl;
        if (url == null) return;
        toast(getString(R.string.st_update_downloading));
        worker.execute(() -> {
            try {
                android.content.pm.PackageInstaller installer = getPackageManager().getPackageInstaller();
                int id = installer.createSession(new android.content.pm.PackageInstaller.SessionParams(
                        android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL));
                try (android.content.pm.PackageInstaller.Session session = installer.openSession(id)) {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    try (java.io.InputStream in = c.getInputStream();
                         java.io.OutputStream out = session.openWrite("update", 0, -1)) {
                        byte[] buffer = new byte[65536];
                        int n;
                        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                        session.fsync(out);
                    }
                    Intent result = new Intent(ACTION_INSTALL).setPackage(getPackageName());
                    android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(this, id, result,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE);
                    session.commit(pending.getIntentSender());
                }
            } catch (java.io.IOException | RuntimeException failed) {
                main.post(() -> toast(getString(R.string.st_update_failed)));
            }
        });
    }

    @Override public void exportSettings() {
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            for (Map.Entry<String, ?> entry : getSharedPreferences("luma_home", MODE_PRIVATE).getAll().entrySet()) {
                Object value = entry.getValue();
                if (entry.getKey().startsWith("stage_")
                        && (value instanceof String || value instanceof Boolean || value instanceof Integer)) {
                    json.put(entry.getKey(), value);
                }
            }
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, "horizon-home-settings.json");
            values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            values.put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new java.io.IOException("no uri");
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                out.write(json.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            toast(getString(R.string.st_backup_saved));
        } catch (java.io.IOException | org.json.JSONException | RuntimeException failed) {
            toast(getString(R.string.st_backup_failed));
        }
    }

    @Override public void importSettings() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(pick, REQ_IMPORT);
        } catch (RuntimeException noPicker) {
            importLatestExport();
        }
    }

    /** Fallback for TVs without a file picker: the newest file this app saved in Downloads. */
    private void importLatestExport() {
        try (Cursor cursor = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Downloads._ID}, MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                new String[]{"horizon-home-settings%"}, MediaStore.Downloads.DATE_ADDED + " DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                applyImport(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)));
                return;
            }
        } catch (RuntimeException unavailable) {
            // Fall through to the message.
        }
        toast(getString(R.string.st_backup_nofile));
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            applyImport(data.getData());
        }
    }

    private void applyImport(android.net.Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            org.json.JSONObject json = new org.json.JSONObject(readAll(in));
            android.content.SharedPreferences.Editor edit = getSharedPreferences("luma_home", MODE_PRIVATE).edit();
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = json.get(key);
                if (!key.startsWith("stage_")) continue;
                if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) edit.putInt(key, (Integer) value);
                else if (value instanceof String) edit.putString(key, (String) value);
            }
            edit.commit();
            toast(getString(R.string.st_backup_loaded));
            recreate();
        } catch (java.io.IOException | org.json.JSONException | RuntimeException failed) {
            toast(getString(R.string.st_backup_nofile));
        }
    }

    @Override public void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
