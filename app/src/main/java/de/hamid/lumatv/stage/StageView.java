package de.hamid.lumatv.stage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import de.hamid.lumatv.R;

/**
 * Luma's Home, Settings, All apps and editor on one remote-driven canvas. Behaviour follows
 * CleanFrame's interaction spec (docs/interaction-behaviour.md): focus alone never launches or
 * applies, OK commits, Back closes one panel at a time, held keys act once.
 */
public final class StageView extends View {

    /** What the activity provides: launching, system screens and background work. */
    public interface Host {
        void launch(HomeItem item);
        void openTvSettings();
        void openInput(String inputId);
        void openClassic();
        Map<String, String> inputs();
        void work(Runnable task);
        boolean homeVariant();
        void leave();
        void catalogChanged();
        void toast(String text);
        /** Opens a text box for the name in the greeting. */
        void editName();
        /** Text box to rename an app or input on Home. */
        void editLabel(String key, String current);
        /** List of bundled icons to choose from. */
        void chooseMark(String key);
        /** Text box for the app search. */
        void searchApps();
        /** List of installed apps to choose the media app from. */
        void pickMediaApp();
        /** Reads the HorizonHome photo folder; asks for permission first when {@code ask} is true. */
        void scanPhotos(boolean ask);
        /** Package names of recently started apps, newest first. */
        List<String> recents();
        /** Short diagnostic text for one HDMI input. */
        String inputInfo(String inputId);
        /** Asks "really?" and runs {@code yes} when the person agrees. */
        void confirm(String message, Runnable yes);
        /** Text box for the birthday (day.month). */
        void editBirthday();
        /** The app language changed; the activity restarts to apply it. */
        void languageChanged();
        /** Dialog to choose the photo folders used for own photos. */
        void pickFolders();
    }

    static final int S_HOME = 0, S_ALL = 1, S_SETTINGS = 2, S_BACKGROUND = 3, S_WALLPAPER = 4,
            S_ROTATION = 5, S_LAYOUT = 6, S_TYPE = 7, S_FOCUS = 8, S_EDITOR = 9, S_TV = 10,
            S_INPUTS = 11, S_PS4 = 12, S_CLOCK = 13, S_GREETING = 14, S_CONNECTED = 15, S_SLIDESHOW = 16, S_APPS = 17, S_APPEDIT = 18, S_SEARCH = 19, S_LANG = 20, S_STYLE = 21;
    static final int ED_HEAD = 0, ED_LEFT = 1, ED_REMOVE = 2, ED_RIGHT = 3;
    private static final int GROUPS = 6;
    private static final long WALL_DELAY = 200;

    private final Host host;
    private final Look look;
    private final Gfx gfx = new Gfx();
    private final Type type;
    private final Marks marks;
    private final HomePainter homePainter;
    private final SheetPainter sheet;

    private final List<Integer> stack = new ArrayList<>();
    private List<HomeItem> catalog = new ArrayList<>();
    private final Map<String, HomeItem> byKey = new HashMap<>();
    private List<HomeItem> homeItems = new ArrayList<>();

    // Home focus
    private int homeIndex;
    private int homeZone = HomePainter.Z_APPS;
    private int util;
    private int listFirst;
    private final Anim dockOff = new Anim(0), plate = new Anim(0), listOff = new Anim(0), lens = new Anim(0);
    private String clock = "";
    private String greeting = "";
    /** HDMI inputs with a device plugged in; the Inputs control shows only when this is not empty. */
    private Map<String, String> connected = new LinkedHashMap<>();
    private String editKey;
    private String searchQuery = "";
    private boolean homeVisible;
    private String date = "";
    private boolean night;
    /** Crossfade between two photos on Home. */
    private static final long FADE_MS = 1200;
    private Bitmap fadeFrom;
    private long fadeStart;
    private String backdropWall;

    // Photo screensaver: starts after the chosen idle time on Home, any key ends it.
    private static final long SAVER_CHECK_MS = 15000, SAVER_SLIDE_MS = 20000, SAVER_FADE_MS = 1400;
    private boolean saverOn;
    private long lastInput = SystemClock.uptimeMillis();
    private long saverFadeStart;
    private int saverToken;
    private int saverCount;
    private long saverCurStart, saverNextStart;
    private int swallowKey = -1;
    private String saverId;
    private Bitmap saverCur, saverNext;
    private final java.util.Random random = new java.util.Random();
    private final Runnable saverCheck = this::checkSaver;
    private final Runnable saverTick = this::saverAdvance;
    private final android.graphics.Paint saverPaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Rect saverSrc = new android.graphics.Rect();
    private final android.graphics.RectF saverDst = new android.graphics.RectF(0, 0, 1920, 1080);
    private final android.text.TextPaint saverText = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    // Scenes
    private Scene scene;
    private Bitmap backdrop;
    private String backdropKey;
    private Scene candidate;
    private Bitmap candidateBackdrop;
    private String loadingWall;
    private int wallToken;
    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private boolean thumbsRequested;

    // Settings
    private int groupFocus;
    private final Map<Integer, int[]> pageFocus = new HashMap<>();
    private Page page;

    // Wallpaper gallery
    private int wallFocus;
    private boolean wallBack;
    private final Anim wallFirst = new Anim(0);
    private boolean applyWhenLoaded;

    // All apps
    private int allFocus;
    private boolean allHead;
    private final Anim allFirst = new Anim(0);
    private int allFirstRow;

    // Editor
    private int edZone = ED_LEFT, edLeft, edRight;
    private boolean edLifted;
    private List<String> edBeforeLift;
    private final Anim edRightFirst = new Anim(0);
    private int edRightFirstRow;
    private String edMessage;
    private boolean edMessageWarn;

    // Keys
    private int heldKey = -1;
    private boolean longFired;

    // Rotation (visible Photo Home time only)
    private long rotationAccumulated;
    private long visibleSince = -1;
    private final Runnable rotationTick = this::rotateNow;

    public StageView(Context context, Host host, Look look) {
        super(context);
        this.host = host;
        this.look = look;
        type = new Type(context.getAssets());
        marks = new Marks(context);
        homePainter = new HomePainter(gfx, type, marks);
        sheet = new SheetPainter(gfx, type, marks, homePainter);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        stack.add(S_HOME);
        loadScene(look.wallpaper, null);
    }

    // ------------------------------------------------------------------ public API

    public void setClock(String text) {
        night = look.night && Sun.isNight(System.currentTimeMillis(), 48.49, 9.21);
        if (text.equals(clock)) return;
        clock = text;
        invalidate();
    }

    /** Greeting under the clock; an empty text hides it. */
    public void setGreeting(String text) {
        if (text.equals(greeting)) return;
        greeting = text;
        invalidate();
    }

    /** Plugged-in HDMI inputs (id to label). Shows or hides the Inputs control next to All apps. */
    public void setConnectedInputs(Map<String, String> inputs) {
        if (connected.equals(inputs)) return;
        int[] before = utilKinds();
        int kind = before[Math.min(util, before.length - 1)];
        connected = new LinkedHashMap<>(inputs);
        int[] after = utilKinds();
        util = after.length - 1;
        for (int i = 0; i < after.length; i++) if (after[i] == kind) util = i;
        lens.jump(util);
        invalidate();
    }

    /** Visible home controls: All apps, Inputs (only when plugged in), Settings. */
    private int[] utilKinds() {
        if (!showInputs()) return new int[]{HomePainter.UTIL_APPS, HomePainter.UTIL_SEARCH, HomePainter.UTIL_SETTINGS};
        return new int[]{HomePainter.UTIL_APPS, HomePainter.UTIL_SEARCH, HomePainter.UTIL_INPUTS, HomePainter.UTIL_SETTINGS};
    }

    /** Automatic: only with something plugged in (or a media app chosen). Always / never are manual. */
    private boolean showInputs() {
        if (Look.IN_NEVER.equals(look.inputsMode)) return false;
        if (Look.IN_ALWAYS.equals(look.inputsMode)) return true;
        return !connected.isEmpty() || look.mediaPackage() != null;
    }

    private void openUtil() {
        int[] kinds = utilKinds();
        int kind = kinds[Math.min(util, kinds.length - 1)];
        if (kind == HomePainter.UTIL_SEARCH) { host.searchApps(); return; }
        push(kind == HomePainter.UTIL_APPS ? S_ALL : kind == HomePainter.UTIL_INPUTS ? S_CONNECTED : S_SETTINGS);
    }

    /** Date under the clock; an empty text hides it. */
    public void setDate(String text) {
        if (text.equals(date)) return;
        date = text;
        invalidate();
    }

    /** Called after the name was changed, so an open settings page shows the new value. */
    public void nameChanged() {
        refreshPage();
    }

    /** All launchable apps (alphabetical) plus the PS4 input, from the activity's worker. */
    public void setCatalog(List<HomeItem> items) {
        catalog = new ArrayList<>(items);
        byKey.clear();
        for (HomeItem item : items) byKey.put(item.key, item);
        rebuildHome();
        if (top() == S_APPS || top() == S_APPEDIT) refreshPage();
        if (top() == S_ALL) allFocus = Math.min(allFocus, Math.max(0, catalog.size() - 1));
        invalidate();
    }

    /** Home key or a fresh start: close every panel and show Home. */
    public void goHome() {
        if (edLifted) cancelLift();
        if (saverOn) stopSaver();
        stack.clear();
        stack.add(S_HOME);
        homeZone = homeItems.isEmpty() ? HomePainter.Z_UTIL : HomePainter.Z_APPS;
        homeIndex = 0;
        syncHomeScroll(false);
        candidate = null;
        candidateBackdrop = null;
        resumeRotation();
        invalidate();
    }

    public void setHomeVisible(boolean visible) {
        homeVisible = visible;
        if (visible) {
            lastInput = SystemClock.uptimeMillis();
            if (look.recentFirst && top() == S_HOME) { rebuildHome(); homeIndex = 0; syncHomeScroll(false); }
            resumeRotation();
            scheduleSaverCheck();
        } else {
            removeCallbacks(saverCheck);
            if (saverOn) stopSaver();
            pauseRotation();
        }
    }

    /** "Each time Home opens" advances once per launcher start. */
    public void onLauncherStart() {
        if (look.photo() && Look.ROT_OPEN.equals(look.rotation)) {
            String next = nextWallId(look.wallpaper);
            if (look.set("wallpaper", next)) loadScene(next, null);
        }
    }

    // ------------------------------------------------------------------ helpers

    private int top() { return stack.get(stack.size() - 1); }

    private void push(int screen) {
        if (top() == S_HOME) pauseRotation();
        stack.add(screen);
        if (screen == S_EDITOR) enterEditor(null);
        if (screen == S_ALL) { allHead = false; allFocus = Math.min(allFocus, Math.max(0, catalog.size() - 1)); scrollAll(false); }
        if (screen == S_WALLPAPER) enterWallpaper();
        if (isPage(screen)) {
            page = buildPage(screen);
            if (!pageFocus.containsKey(screen)) pageFocus.put(screen, new int[]{firstEnabledRow(page), 0});
            fixPageFocus();
        }
        requestThumbs();
        invalidate();
    }

    private void pop() {
        if (stack.size() <= 1) return;
        int leaving = top();
        if (leaving == S_WALLPAPER) { candidate = null; candidateBackdrop = null; wallToken++; applyWhenLoaded = false; }
        if (leaving == S_EDITOR && edLifted) cancelLift();
        stack.remove(stack.size() - 1);
        if (isPage(top())) {
            page = buildPage(top());
            fixPageFocus();
        }
        if (top() == S_HOME) resumeRotation();
        invalidate();
    }

    private static boolean isPage(int screen) {
        return screen == S_BACKGROUND || screen == S_ROTATION || screen == S_LAYOUT || screen == S_TYPE
                || screen == S_FOCUS || screen == S_TV || screen == S_INPUTS || screen == S_PS4 || screen == S_CLOCK || screen == S_GREETING || screen == S_CONNECTED
                || screen == S_SLIDESHOW || screen == S_APPS || screen == S_APPEDIT || screen == S_SEARCH || screen == S_LANG || screen == S_STYLE;
    }

    private long dur(long ms) { return look.motion ? ms : 0; }

    private String s(int id) { return getContext().getString(id); }

    private String s(int id, Object... args) { return getContext().getString(id, args); }

    // ------------------------------------------------------------------ Home items

    private void rebuildHome() {
        List<HomeItem> items = new ArrayList<>();
        for (String key : look.homeKeys()) {
            HomeItem item = byKey.get(key);
            if (item != null) items.add(item);
        }
        if (look.recentFirst) {
            final List<String> order = host.recents();
            items.sort((a, b) -> Integer.compare(recentRank(order, a.key), recentRank(order, b.key)));
        }
        homeItems = items;
        homeIndex = HomePainter.clampInt(homeIndex, 0, Math.max(0, items.size() - 1));
        if (items.isEmpty()) homeZone = HomePainter.Z_UTIL;
        syncHomeScroll(false);
    }

    private static int recentRank(List<String> order, String key) {
        int i = order.indexOf(key);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private void syncHomeScroll(boolean animate) {
        int n = homeItems.size();
        long d = animate ? dur(180) : 0;
        if (look.dock()) {
            HomePainter.Geom geom = HomePainter.dock(look.apps, n);
            float target = HomePainter.dockOffset(geom, n, homeIndex, dockOff.target());
            if (d == 0) dockOff.jump(target); else dockOff.to(target, d);
        } else {
            listFirst = HomePainter.listFirst(n, homeIndex, listFirst);
            float target = listFirst * HomePainter.PH_PITCH;
            if (d == 0) listOff.jump(target); else listOff.to(target, d);
        }
        if (d == 0) plate.jump(homeIndex); else plate.to(homeIndex, d);
    }

    private Set<String> homeKeySet() { return new HashSet<>(look.homeKeys()); }

    // ------------------------------------------------------------------ scenes

    private void loadScene(String wallId, Runnable then) {
        final Walls.Wall wall = Walls.get(wallId);
        loadingWall = wall.id;
        final Context context = getContext().getApplicationContext();
        host.work(() -> {
            Scene loaded = Scene.load(context, wall, false);
            post(() -> {
                if (loaded == null || !wall.id.equals(look.wallpaper)) return;
                scene = loaded;
                loadingWall = null;
                backdropKey = null;
                ensureBackdrop();
                if (then != null) then.run();
                invalidate();
            });
        });
    }

    private void ensureBackdrop() {
        if (scene == null || !look.photo()) return;
        String key = scene.wall.id + "|" + look.layout + "|" + System.identityHashCode(scene);
        if (key.equals(backdropKey)) return;
        Bitmap previous = backdrop;
        backdrop = homePainter.composeBackdrop(scene, look);
        String wallId = scene.wall.id;
        if (look.fade && look.motion && previous != null && backdrop != null
                && backdropWall != null && !backdropWall.equals(wallId) && top() == S_HOME) {
            fadeFrom = previous;
            fadeStart = SystemClock.uptimeMillis();
        }
        backdropWall = wallId;
        backdropKey = key;
    }

    private void requestThumbs() {
        if (thumbsRequested || top() == S_HOME) return;
        thumbsRequested = true;
        final Context context = getContext().getApplicationContext();
        host.work(() -> {
            for (Walls.Wall wall : Walls.ALL) {
                Bitmap thumb = Scene.thumb(context, wall);
                if (thumb == null) continue;
                post(() -> {
                    thumbs.put(wall.id, thumb);
                    invalidate();
                });
            }
        });
    }

    // ------------------------------------------------------------------ photos, search, screensaver

    /** Photos allowed by the chosen source; falls back to the built-in ones when no own photo exists. */
    private List<String> wallPool() {
        List<String> pool = new ArrayList<>();
        List<String> mine = Walls.ownIds();
        boolean own = !Look.SRC_BUILTIN.equals(look.photoSource) && !mine.isEmpty();
        boolean builtin = !Look.SRC_OWN.equals(look.photoSource) || mine.isEmpty();
        if (builtin) for (Walls.Wall wall : Walls.ALL) pool.add(wall.id);
        if (own) pool.addAll(mine);
        return pool;
    }

    private String nextWallId(String current) {
        List<String> pool = wallPool();
        if (pool.isEmpty()) return current;
        if (pool.size() == 1) return pool.get(0);
        if (look.shuffle) {
            String pick = current;
            for (int tries = 0; tries < 10 && pick.equals(current); tries++) pick = pool.get(random.nextInt(pool.size()));
            return pick;
        }
        int i = pool.indexOf(current);
        return pool.get((i + 1) % pool.size());
    }

    /** Called after the HorizonHome folder was read. */
    public void photosChanged() {
        if (isPage(top())) refreshPage();
        invalidate();
    }

    /** Installed apps (no HDMI inputs), for the media app list. */
    public List<HomeItem> apps() {
        List<HomeItem> out = new ArrayList<>();
        for (HomeItem item : catalog) if (item.app != null) out.add(item);
        return out;
    }

    /** Shows the search results for the given text. */
    public void showSearch(String query) {
        searchQuery = query == null ? "" : query;
        if (top() == S_SEARCH) refreshPage(); else push(S_SEARCH);
    }

    private void scheduleSaverCheck() {
        removeCallbacks(saverCheck);
        if (look.saverMinutes > 0 && !saverOn && homeVisible && isAttachedToWindow()) postDelayed(saverCheck, SAVER_CHECK_MS);
    }

    private void checkSaver() {
        if (look.saverMinutes <= 0 || !homeVisible || saverOn) return;
        boolean idle = SystemClock.uptimeMillis() - lastInput >= look.saverMinutes * 60000L;
        if (idle && top() == S_HOME) startSaver(); else scheduleSaverCheck();
    }

    private void startSaver() {
        saverOn = true;
        saverCur = null;
        saverNext = null;
        saverId = null;
        pauseRotation();
        saverAdvance();
        invalidate();
    }

    private void stopSaver() {
        saverOn = false;
        saverToken++;
        removeCallbacks(saverTick);
        saverCur = null;
        saverNext = null;
        lastInput = SystemClock.uptimeMillis();
        resumeRotation();
        scheduleSaverCheck();
        invalidate();
    }

    private void saverAdvance() {
        removeCallbacks(saverTick);
        if (!saverOn) return;
        final int token = ++saverToken;
        final String id = nextWallId(saverId == null ? look.wallpaper : saverId);
        saverId = id;
        final Walls.Wall wall = Walls.get(id);
        final Context context = getContext().getApplicationContext();
        host.work(() -> {
            Bitmap picture = Scene.photo(context, wall);
            post(() -> {
                if (!saverOn || token != saverToken) return;
                if (picture != null) {
                    saverCount++;
                    long shown = SystemClock.uptimeMillis();
                    if (saverCur == null) {
                        saverCur = picture;
                        saverCurStart = shown;
                    } else {
                        saverNext = picture;
                        saverFadeStart = shown;
                        saverNextStart = shown;
                    }
                }
                postDelayed(saverTick, SAVER_SLIDE_MS);
                invalidate();
            });
        });
    }

    private void drawSaver(Canvas c, long now) {
        c.drawColor(0xFF000000);
        if (saverCur != null) {
            saverPaint.setAlpha(255);
            drawCover(c, saverCur, now - saverCurStart);
        }
        if (saverNext != null) {
            float fade = Math.min(1f, (now - saverFadeStart) / (float) SAVER_FADE_MS);
            saverPaint.setAlpha((int) (255 * fade));
            drawCover(c, saverNext, now - saverNextStart);
            saverPaint.setAlpha(255);
            if (fade >= 1f) {
                saverCur = saverNext;
                saverCurStart = saverNextStart;
                saverNext = null;
            }
        }
        // The clock moves to another corner with every picture.
        int corner = saverCount % 4;
        boolean right = corner == 1 || corner == 3, bottom = corner < 2;
        saverText.setTypeface(type.home(look.font, Type.CLOCK));
        saverText.setTextSize(88);
        saverText.setColor(0xF2FFFFFF);
        saverText.setShadowLayer(14, 0, 3, 0x99000000);
        saverText.setTextAlign(right ? android.graphics.Paint.Align.RIGHT : android.graphics.Paint.Align.LEFT);
        c.drawText(clock, right ? 1800 : 120, bottom ? 975 : 160, saverText);
        postInvalidateOnAnimation();
    }

    /** Fills the screen with the picture and zooms in very slowly (Ken Burns). */
    private void drawCover(Canvas c, Bitmap b, long age) {
        int bw = b.getWidth(), bh = b.getHeight();
        if (bw <= 0 || bh <= 0) return;
        float scale = Math.max(1920f / bw, 1080f / bh);
        int sw = Math.min(bw, Math.round(1920f / scale)), sh = Math.min(bh, Math.round(1080f / scale));
        int sx = (bw - sw) / 2, sy = (bh - sh) / 2;
        saverSrc.set(sx, sy, sx + sw, sy + sh);
        float zoom = 1f + .07f * Math.min(1f, Math.max(0, age) / (float) (SAVER_SLIDE_MS + SAVER_FADE_MS));
        int save = c.save();
        c.scale(zoom, zoom, saverCount % 2 == 0 ? 800 : 1120, 540);
        c.drawBitmap(b, saverSrc, saverDst, saverPaint);
        c.restoreToCount(save);
    }

    // ------------------------------------------------------------------ rotation

    private long rotationMinutes() {
        switch (look.rotation) {
            case Look.ROT_5: return 5;
            case Look.ROT_15: return 15;
            case Look.ROT_60: return 60;
            default: return 0;
        }
    }

    private void resumeRotation() {
        removeCallbacks(rotationTick);
        long minutes = rotationMinutes();
        if (minutes == 0 || !look.photo() || top() != S_HOME || !isAttachedToWindow()) { visibleSince = -1; return; }
        visibleSince = SystemClock.uptimeMillis();
        long remaining = Math.max(1000, minutes * 60000 - rotationAccumulated);
        postDelayed(rotationTick, remaining);
    }

    private void pauseRotation() {
        removeCallbacks(rotationTick);
        if (visibleSince >= 0) rotationAccumulated += SystemClock.uptimeMillis() - visibleSince;
        visibleSince = -1;
    }

    private void rotateNow() {
        rotationAccumulated = 0;
        visibleSince = -1;
        String next = nextWallId(look.wallpaper);
        if (look.set("wallpaper", next)) loadScene(next, null);
        resumeRotation();
    }

    private void restartRotation() {
        pauseRotation();
        rotationAccumulated = 0;
        if (top() == S_HOME) resumeRotation();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(rotationTick);
        removeCallbacks(saverCheck);
        removeCallbacks(saverTick);
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        int save = canvas.save();
        canvas.scale(getWidth() / 1920f, getHeight() / 1080f);
        int screen = top();
        if (saverOn) {
            drawSaver(canvas, now);
        } else if (screen == S_HOME) {
            ensureBackdrop();
            homePainter.draw(canvas, homeFrame(homeZone, scene, backdrop, now));
        } else {
            drawSheet(canvas, screen, now);
        }
        canvas.restoreToCount(save);
        if (dockOff.running(now) || plate.running(now) || listOff.running(now) || lens.running(now)
                || allFirst.running(now) || wallFirst.running(now) || edRightFirst.running(now) || fadeFrom != null) {
            postInvalidateOnAnimation();
        }
    }

    private HomePainter.Frame homeFrame(int zone, Scene sc, Bitmap bd, long now) {
        HomePainter.Frame f = new HomePainter.Frame();
        f.look = look;
        f.scene = sc;
        f.backdrop = bd;
        f.date = date;
        f.night = night;
        if (fadeFrom != null) {
            long elapsed = now - fadeStart;
            if (elapsed < FADE_MS) {
                f.fadeFrom = fadeFrom;
                f.fade = elapsed / (float) FADE_MS;
            } else {
                fadeFrom = null;
            }
        }
        f.items = homeItems;
        f.anchor = homeIndex;
        f.zone = homeItems.isEmpty() && zone == HomePainter.Z_APPS ? HomePainter.Z_UTIL : zone;
        int[] kinds = utilKinds();
        f.utilKinds = kinds;
        f.util = Math.min(util, kinds.length - 1);
        f.off = look.dock() ? dockOff.value(now) : listOff.value(now);
        f.plate = plate.value(now);
        f.lens = lens.value(now);
        f.clock = clock;
        f.greeting = greeting;
        f.utilLabels = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            f.utilLabels[i] = kinds[i] == HomePainter.UTIL_APPS ? s(R.string.st_all_apps)
                    : kinds[i] == HomePainter.UTIL_SEARCH ? s(R.string.st_search)
                    : kinds[i] == HomePainter.UTIL_INPUTS ? s(R.string.st_inputs) : s(R.string.st_settings);
        }
        f.emptyText = s(R.string.st_home_empty);
        return f;
    }

    private void drawSheet(Canvas c, int screen, long now) {
        SheetPainter.Ink k = SheetPainter.Ink.of(look);
        sheet.background(c, look, scene);
        switch (screen) {
            case S_SETTINGS:
                sheet.overview(c, k, s(R.string.st_settings), groupNames(), groupValues(), groupFocus, s(R.string.st_hint_open));
                break;
            case S_WALLPAPER:
                drawWallpaperPage(c, k, now);
                break;
            case S_ALL:
                sheet.titleWithCount(c, k, s(R.string.st_all_apps), String.valueOf(catalog.size()));
                sheet.headButton(c, look, k, scene, s(R.string.st_edit_home), "home", allHead, false);
                sheet.allApps(c, look, k, catalog, homeKeySet(), allFocus, allHead, allFirst.value(now), s(R.string.st_on_home));
                sheet.hint(c, k, "OK", s(R.string.st_all_hint));
                break;
            case S_EDITOR:
                drawEditor(c, k, now);
                break;
            default:
                if (page != null) {
                    int[] pf = pageFocus.get(screen);
                    sheet.page(c, k, page, pf[0], pf[1], thumbs);
                    if (page.preview) {
                        ensureBackdrop();
                        sheet.preview(c, k, homeFrame(HomePainter.Z_PREVIEW, scene, backdrop, now));
                    }
                }
                break;
        }
    }

    private void drawWallpaperPage(Canvas c, SheetPainter.Ink k, long now) {
        int n = Walls.ALL.length;
        String[] names = new String[n], ids = new String[n];
        for (int i = 0; i < n; i++) { names[i] = s(Walls.ALL[i].name); ids[i] = Walls.ALL[i].id; }
        int applied = Walls.indexOf(look.wallpaper);
        Walls.Wall shown = Walls.ALL[wallFocus];
        String state;
        boolean candidateReady = candidate != null && candidate.wall.id.equals(shown.id);
        if (wallFocus == applied) state = s(R.string.st_wall_current);
        else if (!candidateReady) state = s(R.string.st_wall_loading, names[wallFocus]);
        else state = s(R.string.st_wall_preview);
        sheet.wallpapers(c, k, s(R.string.st_background), s(R.string.st_wallpaper), names, ids, applied, wallFocus,
                wallFirst.value(now), wallBack, thumbs, names[wallFocus], s(R.string.st_count_of, wallFocus + 1, n), state,
                s(R.string.st_hint_photo));
        Scene sc = candidate != null ? candidate : scene;
        Bitmap bd = candidate != null ? candidateBackdrop : backdrop;
        Look shownLook = look;
        HomePainter.Frame frame = homeFrame(HomePainter.Z_PREVIEW, sc, bd, now);
        frame.look = shownLook;
        sheet.preview(c, k, frame);
    }

    private void drawEditor(Canvas c, SheetPainter.Ink k, long now) {
        sheet.title(c, k, s(R.string.st_group_apps), 72, 64);
        sheet.sub(c, edMessageWarn ? k.warn : k.mute, edMessage != null ? edMessage : s(R.string.st_editor_hint));
        sheet.headButton(c, look, k, scene, s(R.string.st_done), null, edZone == ED_HEAD, false);
        List<HomeItem> right = editorRight();
        sheet.editor(c, look, k, homeItems, right, edZone, edLeft, edRight, edLifted, edRightFirst.value(now),
                s(R.string.st_on_home), s(R.string.st_not_on_home), s(R.string.st_remove), s(R.string.st_add),
                s(R.string.st_count_of, homeItems.size(), Look.HOME_MAX));
    }

    // ------------------------------------------------------------------ settings model

    private String[] groupNames() {
        return new String[]{s(R.string.st_background), s(R.string.st_layout), s(R.string.st_typography),
                s(R.string.st_focus), s(R.string.st_group_apps), s(R.string.st_group_tv)};
    }

    private String[] groupValues() {
        String bg = look.photo() ? s(Walls.get(look.wallpaper).name)
                : Look.BG_LIGHT.equals(look.bg) ? s(R.string.st_bg_light) : s(R.string.st_bg_dark);
        String layout = (look.dock() ? s(R.string.st_layout_dock) : s(R.string.st_layout_list)) + ", " + showName(look.apps);
        String focus = look.glass() ? s(R.string.st_focus_glass) + (look.materialApplies() ? ", " + materialName(look.material) : "")
                : s(R.string.st_focus_type);
        int n = homeItems.size();
        String apps = getResources().getQuantityString(R.plurals.st_apps_count, n, n);
        return new String[]{bg, layout, fontName(look.font), focus, apps, s(R.string.st_tv_value)};
    }

    private String showName(String v) {
        return Look.SHOW_TEXT.equals(v) ? s(R.string.st_show_text) : Look.SHOW_ICONS.equals(v) ? s(R.string.st_show_icons) : s(R.string.st_show_both);
    }

    private String materialName(String v) {
        return Look.MAT_FROST.equals(v) ? s(R.string.st_mat_frost) : Look.MAT_CLEAR.equals(v) ? s(R.string.st_mat_clear) : s(R.string.st_mat_soft);
    }

    private String fontName(String v) {
        if (Look.FONT_GEIST.equals(v)) return "Geist";
        if (Look.FONT_NEWSREADER.equals(v)) return "Newsreader";
        if (Look.FONT_ROBOTO.equals(v)) return "Roboto";
        if (Look.FONT_CONDENSED.equals(v)) return "Roboto Condensed";
        if (Look.FONT_SERIF.equals(v)) return "Noto Serif";
        return "Manrope";
    }

    /** A section with the two choices On and Off. */
    private void toggleSection(Page p, String label, boolean value, java.util.function.Predicate<Boolean> setter) {
        Page.Section sec = p.section(label, true);
        Page.Opt on = sec.add(new Page.Opt(s(R.string.st_on), () -> {
            if (setter.test(true)) refreshPage(); else host.toast(s(R.string.st_save_failed));
        }));
        on.checked = value;
        Page.Opt off = sec.add(new Page.Opt(s(R.string.st_greeting_off), () -> {
            if (setter.test(false)) refreshPage(); else host.toast(s(R.string.st_save_failed));
        }));
        off.checked = !value;
    }

    private void updateNight() {
        night = look.night && Sun.isNight(System.currentTimeMillis(), 48.49, 9.21);
        invalidate();
    }

    private String languageName(String code) {
        if ("de".equals(code)) return "Deutsch";
        if ("en".equals(code)) return "English";
        if ("ru".equals(code)) return "\u0420\u0443\u0441\u0441\u043a\u0438\u0439";
        return s(R.string.st_lang_system);
    }

    private String clockName(int mode) {
        return mode == 1 ? s(R.string.st_clock_24) : mode == 2 ? s(R.string.st_clock_12) : s(R.string.st_clock_system);
    }

    private String rotationName(String v) {
        switch (v) {
            case Look.ROT_OPEN: return s(R.string.st_rot_open);
            case Look.ROT_5: return s(R.string.st_rot_5);
            case Look.ROT_15: return s(R.string.st_rot_15);
            case Look.ROT_60: return s(R.string.st_rot_60);
            default: return s(R.string.st_rot_fixed);
        }
    }

    private Page buildPage(int screen) {
        Page p;
        switch (screen) {
            case S_BACKGROUND: {
                p = new Page(screen, s(R.string.st_settings), s(R.string.st_background), true);
                Page.Section wall = p.section(s(R.string.st_wallpaper), false);
                Page.Opt w = wall.add(new Page.Opt(s(Walls.get(look.wallpaper).name), () -> push(S_WALLPAPER)));
                w.swatch = Page.SW_THUMB;
                w.thumb = look.wallpaper;
                w.forward = true;
                w.enabled = look.photo();
                Page.Section rot = p.section(s(R.string.st_rotation), false);
                Page.Opt r = rot.add(new Page.Opt(rotationName(look.rotation), () -> push(S_ROTATION)));
                r.forward = true;
                r.alignWithSwatch = true;
                r.enabled = look.photo();
                Page.Opt show = rot.add(new Page.Opt(s(R.string.st_slideshow), () -> push(S_SLIDESHOW)));
                show.forward = true;
                show.alignWithSwatch = true;
                p.hint = s(R.string.st_hint_apply);
                break;
            }
            case S_ROTATION: {
                p = new Page(screen, s(R.string.st_background), s(R.string.st_rotation), true);
                p.top = 260;
                p.rowHeight = 88;
                Page.Section sec = p.section(null, false);
                for (String v : Look.ROTATIONS) {
                    Page.Opt o = sec.add(new Page.Opt(rotationName(v), () -> {
                        if (apply("rotation", v)) restartRotation();
                    }));
                    o.checked = v.equals(look.rotation);
                }
                p.hint = s(R.string.st_hint_apply);
                break;
            }
            case S_LAYOUT: {
                p = new Page(screen, s(R.string.st_settings), s(R.string.st_layout), true);
                p.sectionGap = 64;
                Page.Section l = p.section(s(R.string.st_layout), true);
                for (String v : Look.LAYOUTS) {
                    Page.Opt o = l.add(new Page.Opt(Look.LAYOUT_DOCK.equals(v) ? s(R.string.st_layout_dock) : s(R.string.st_layout_list), () -> apply("layout", v)));
                    o.checked = v.equals(look.layout);
                }
                Page.Section a = p.section(s(R.string.st_app_display), true);
                for (String v : Look.SHOWS) {
                    Page.Opt o = a.add(new Page.Opt(showName(v), () -> apply("apps", v)));
                    o.checked = v.equals(look.apps);
                }
                Page.Section u = p.section(s(R.string.st_controls), true);
                for (String v : Look.SHOWS) {
                    Page.Opt o = u.add(new Page.Opt(showName(v), () -> apply("controls", v)));
                    o.checked = v.equals(look.controls);
                }
                p.hint = s(R.string.st_hint_apply);
                break;
            }
            case S_TYPE: {
                p = new Page(screen, s(R.string.st_settings), s(R.string.st_typography), true);
                p.top = 212;
                p.descHeight = 92;
                Page.Section sec = p.section(null, false);
                int[] desc = {R.string.st_font_manrope, R.string.st_font_geist, R.string.st_font_newsreader,
                        R.string.st_font_roboto, R.string.st_font_condensed, R.string.st_font_serif};
                for (int i = 0; i < Look.FONTS.size(); i++) {
                    String v = Look.FONTS.get(i);
                    Page.Opt o = sec.add(new Page.Opt(fontName(v), () -> apply("font", v)));
                    o.checked = v.equals(look.font);
                    o.description = s(desc[i]);
                    o.face = type.home(v, Type.UI);
                }
                p.hint = s(R.string.st_hint_apply);
                break;
            }
            case S_FOCUS: {
                p = new Page(screen, s(R.string.st_settings), s(R.string.st_focus), true);
                p.sectionGap = 32;
                Page.Section f = p.section(s(R.string.st_focus), true);
                for (String v : Look.FOCUSES) {
                    Page.Opt o = f.add(new Page.Opt(Look.FOCUS_GLASS.equals(v) ? s(R.string.st_focus_glass) : s(R.string.st_focus_type), () -> apply("focus", v)));
                    o.checked = v.equals(look.focus);
                }
                Page.Section m = p.section(s(R.string.st_material), true);
                boolean on = look.materialApplies();
                for (String v : Look.MATERIALS) {
                    Page.Opt o = m.add(new Page.Opt(materialName(v), () -> apply("material", v)));
                    o.checked = v.equals(look.material);
                    o.enabled = on;
                }
                if (!on) m.reason = s(R.string.st_material_off);
                Page.Section mo = p.section(s(R.string.st_motion), true);
                Page.Opt smooth = mo.add(new Page.Opt(s(R.string.st_motion_on), () -> { if (look.setMotion(true)) refreshPage(); }));
                smooth.checked = look.motion;
                Page.Opt instant = mo.add(new Page.Opt(s(R.string.st_motion_off), () -> { if (look.setMotion(false)) refreshPage(); }));
                instant.checked = !look.motion;
                p.hint = s(R.string.st_hint_apply);
                break;
            }
            case S_TV: {
                p = new Page(screen, s(R.string.st_settings), s(R.string.st_group_tv), false);
                p.width = 1056;
                p.top = 212;
                p.rowHeight = 64;
                p.sectionGap = 24;
                Page.Section tv = p.section(s(R.string.st_tv_section), false);
                tv.add(new Page.Opt(s(R.string.st_tv_settings), host::openTvSettings));
                Page.Opt inputs = tv.add(new Page.Opt(s(R.string.st_inputs), () -> push(S_INPUTS)));
                inputs.forward = true;
                Page.Section luma = p.section(s(R.string.st_luma_section), false);
                Page.Opt lang = luma.add(new Page.Opt(s(R.string.st_language) + ": " + languageName(look.language), () -> push(S_LANG)));
                lang.forward = true;
                luma.add(new Page.Opt(s(R.string.st_classic), host::openClassic));
                Page.Opt clockOpt = luma.add(new Page.Opt(s(R.string.st_clock), () -> push(S_CLOCK)));
                clockOpt.forward = true;
                String greetValue = !look.greetingOn() ? s(R.string.st_greeting_off)
                        : look.userName().isEmpty() ? s(R.string.st_name_none) : look.userName();
                Page.Opt greetOpt = luma.add(new Page.Opt(s(R.string.st_greeting) + ": " + greetValue, () -> push(S_GREETING)));
                greetOpt.forward = true;
                Page.Opt customize = luma.add(new Page.Opt(s(R.string.st_customize), () -> push(S_APPS)));
                customize.forward = true;
                Page.Opt style = luma.add(new Page.Opt(s(R.string.st_style), () -> push(S_STYLE)));
                style.forward = true;
                p.footnote = s(R.string.st_credits);
                p.hint = s(R.string.st_hint_select);
                break;
            }
            case S_STYLE: {
                p = new Page(screen, s(R.string.st_group_tv), s(R.string.st_style), false);
                p.width = 1056;
                p.top = 212;
                p.rowHeight = 76;
                p.sectionGap = 24;
                toggleSection(p, s(R.string.st_glass), look.glassUi, v -> look.setGlassUi(v));
                toggleSection(p, s(R.string.st_sound), look.sound, v -> look.setSound(v));
                toggleSection(p, s(R.string.st_night), look.night, v -> {
                    boolean saved = look.setNight(v);
                    updateNight();
                    return saved;
                });
                int[] acNames = {R.string.st_accent_white, R.string.st_accent_gold, R.string.st_accent_coral,
                        R.string.st_accent_mint, R.string.st_accent_sky, R.string.st_accent_lavender};
                for (int group = 0; group < 2; group++) {
                    Page.Section ac = p.section(group == 0 ? s(R.string.st_accent) : null, true);
                    for (int i = group * 3; i < group * 3 + 3; i++) {
                        final String v = Look.ACCENTS.get(i);
                        Page.Opt o = ac.add(new Page.Opt(s(acNames[i]), () -> {
                            if (look.setAccent(v)) refreshPage(); else host.toast(s(R.string.st_save_failed));
                        }));
                        o.checked = v.equals(look.accent);
                    }
                }
                p.hint = s(R.string.st_hint_apply);
                break;
            }
            case S_LANG: {
                p = new Page(screen, s(R.string.st_group_tv), s(R.string.st_language), false);
                p.width = 1056;
                p.top = 260;
                p.rowHeight = 88;
                Page.Section sec = p.section(null, false);
                for (String code : Look.LANGUAGES) {
                    final String v = code;
                    Page.Opt o = sec.add(new Page.Opt(languageName(v), () -> {
                        if (v.equals(look.language)) return;
                        if (look.setLanguage(v)) host.languageChanged(); else host.toast(s(R.string.st_save_failed));
                    }));
                    o.checked = v.equals(look.language);
                }
                p.hint = s(R.string.st_hint_apply);
                break;
            }
            case S_INPUTS: {
                p = new Page(screen, s(R.string.st_group_tv), s(R.string.st_inputs), false);
                p.width = 1056;
                p.top = 212;
                p.descHeight = 96;
                p.sectionGap = 28;
                Page.Section sec = p.section(null, false);
                Map<String, String> inputLabels = host.inputs();
                String ps4Id = look.ps4InputId();
                for (Map.Entry<String, String> e : inputLabels.entrySet()) {
                    final String id = e.getKey();
                    Page.Opt o = sec.add(new Page.Opt(e.getValue(), () -> host.openInput(id)));
                    String info = host.inputInfo(id);
                    o.description = id.equals(ps4Id) ? "PS4 \u00b7 " + info : info;
                }
                if (inputLabels.isEmpty()) {
                    Page.Opt none = sec.add(new Page.Opt(s(R.string.inputs_none), null));
                    none.enabled = false;
                }
                Page.Section mode = p.section(s(R.string.st_inputs_show), true);
                int[] modeNames = {R.string.st_in_auto, R.string.st_in_always, R.string.st_in_never};
                for (int i = 0; i < Look.INPUT_MODES.size(); i++) {
                    final String v = Look.INPUT_MODES.get(i);
                    Page.Opt o = mode.add(new Page.Opt(s(modeNames[i]), () -> {
                        if (look.setInputsMode(v)) { host.catalogChanged(); refreshPage(); }
                        else host.toast(s(R.string.st_save_failed));
                    }));
                    o.checked = v.equals(look.inputsMode);
                }
                Page.Section med = p.section(null, false);
                Page.Opt mediaOpt = med.add(new Page.Opt(s(R.string.st_media), host::pickMediaApp));
                HomeItem chosen = look.mediaPackage() == null ? null : byKey.get(look.mediaPackage());
                mediaOpt.description = chosen != null ? chosen.label : s(R.string.st_media_none);
                mediaOpt.forward = true;
                p.hint = s(R.string.st_hint_input);
                break;
            }
            case S_PS4: {
                p = new Page(screen, s(R.string.st_group_tv), s(R.string.st_ps4), false);
                p.width = 1056;
                p.top = 260;
                Page.Section sec = p.section(s(R.string.st_ps4_question), false);
                String ps4Id = look.ps4InputId();
                Page.Opt none = sec.add(new Page.Opt(s(R.string.st_ps4_none), () -> assignPs4(null)));
                none.checked = ps4Id == null;
                for (Map.Entry<String, String> e : host.inputs().entrySet()) {
                    final String id = e.getKey();
                    Page.Opt o = sec.add(new Page.Opt(e.getValue(), () -> assignPs4(id)));
                    o.checked = id.equals(ps4Id);
                }
                p.hint = s(R.string.st_hint_apply);
                break;
            }
            case S_CLOCK: {
                p = new Page(screen, s(R.string.st_group_tv), s(R.string.st_clock), false);
                p.width = 1056;
                p.top = 260;
                p.rowHeight = 88;
                Page.Section sec = p.section(null, false);
                for (int i = 0; i < 3; i++) {
                    final int mode = i;
                    Page.Opt o = sec.add(new Page.Opt(clockName(i), () -> {
                        if (look.setClockMode(mode)) { host.catalogChanged(); refreshPage(); }
                    }));
                    o.checked = look.clockMode() == i;
                }
                Page.Section sz = p.section(s(R.string.st_clock_size), true);
                int[] szNames = {R.string.st_size_normal, R.string.st_size_large, R.string.st_size_xl};
                for (int i = 0; i < 3; i++) {
                    final int size = i;
                    Page.Opt o = sz.add(new Page.Opt(s(szNames[i]), () -> {
                        if (look.setClockSize(size)) refreshPage(); else host.toast(s(R.string.st_save_failed));
                    }));
                    o.checked = look.clockSize == i;
                }
                Page.Section dt = p.section(s(R.string.st_date), true);
                Page.Opt dateYes = dt.add(new Page.Opt(s(R.string.st_on), () -> {
                    if (look.setDateOn(true)) { host.catalogChanged(); refreshPage(); }
                }));
                dateYes.checked = look.dateOn;
                Page.Opt dateNo = dt.add(new Page.Opt(s(R.string.st_greeting_off), () -> {
                    if (look.setDateOn(false)) { host.catalogChanged(); refreshPage(); }
                }));
                dateNo.checked = !look.dateOn;
                p.hint = s(R.string.st_hint_apply);
                break;
            }
            case S_CONNECTED: {
                p = new Page(screen, s(R.string.menu_title), s(R.string.st_inputs), false);
                p.width = 1056;
                p.top = 260;
                Page.Section sec = p.section(null, false);
                Map<String, String> list = Look.IN_ALWAYS.equals(look.inputsMode) ? host.inputs() : connected;
                for (Map.Entry<String, String> e : list.entrySet()) {
                    final String id = e.getKey();
                    sec.add(new Page.Opt(e.getValue(), () -> host.openInput(id)));
                }
                String mediaPkg = look.mediaPackage();
                final HomeItem media = mediaPkg == null ? null : byKey.get(mediaPkg);
                if (media != null) {
                    Page.Opt m = sec.add(new Page.Opt(media.label, () -> host.launch(media)));
                    m.description = s(R.string.st_media_row_desc);
                }
                if (list.isEmpty() && media == null) {
                    Page.Opt none = sec.add(new Page.Opt(s(R.string.inputs_none), null));
                    none.enabled = false;
                }
                p.hint = s(R.string.st_hint_input);
                break;
            }
            case S_SLIDESHOW: {
                p = new Page(screen, s(R.string.st_background), s(R.string.st_slideshow), false);
                p.width = 1056;
                p.top = 212;
                p.rowHeight = 76;
                p.descHeight = 100;
                p.sectionGap = 24;
                Page.Section src = p.section(s(R.string.st_photo_source), true);
                int[] srcNames = {R.string.st_src_builtin, R.string.st_src_own, R.string.st_src_both};
                for (int i = 0; i < Look.SOURCES.size(); i++) {
                    final String v = Look.SOURCES.get(i);
                    Page.Opt o = src.add(new Page.Opt(s(srcNames[i]), () -> {
                        if (look.setPhotoSource(v)) refreshPage(); else host.toast(s(R.string.st_save_failed));
                    }));
                    o.checked = v.equals(look.photoSource);
                }
                Page.Section ord = p.section(s(R.string.st_order), true);
                Page.Opt seq = ord.add(new Page.Opt(s(R.string.st_order_seq), () -> {
                    if (look.setShuffle(false)) refreshPage();
                }));
                seq.checked = !look.shuffle;
                Page.Opt shuf = ord.add(new Page.Opt(s(R.string.st_order_shuffle), () -> {
                    if (look.setShuffle(true)) refreshPage();
                }));
                shuf.checked = look.shuffle;
                Page.Section fd = p.section(s(R.string.st_fade), true);
                Page.Opt fadeOn = fd.add(new Page.Opt(s(R.string.st_on), () -> { if (look.setFade(true)) refreshPage(); }));
                fadeOn.checked = look.fade;
                Page.Opt fadeOff = fd.add(new Page.Opt(s(R.string.st_greeting_off), () -> { if (look.setFade(false)) refreshPage(); }));
                fadeOff.checked = !look.fade;
                Page.Section sav = p.section(s(R.string.st_saver), true);
                for (int minutes : Look.SAVER_CHOICES) {
                    final int m = minutes;
                    Page.Opt o = sav.add(new Page.Opt(m == 0 ? s(R.string.st_greeting_off) : s(R.string.st_minutes, m), () -> {
                        if (look.setSaverMinutes(m)) { scheduleSaverCheck(); refreshPage(); }
                        else host.toast(s(R.string.st_save_failed));
                    }));
                    o.checked = look.saverMinutes == m;
                }
                Page.Section own = p.section(null, false);
                Page.Opt folderOpt = own.add(new Page.Opt(s(R.string.st_folders), host::pickFolders));
                int count = Walls.ownIds().size();
                List<String> chosenFolders = look.photoFolders();
                String folderText = chosenFolders.isEmpty() ? s(R.string.st_folders_all) : s(R.string.st_folders_n, chosenFolders.size());
                folderOpt.description = (count > 0 ? s(R.string.st_own_found, count) : s(R.string.st_own_none)) + " \u00b7 " + folderText;
                folderOpt.forward = true;
                p.hint = s(R.string.st_hint_apply);
                break;
            }
            case S_APPS: {
                p = new Page(screen, s(R.string.st_group_tv), s(R.string.st_customize), false);
                p.width = 1056;
                p.top = 212;
                p.rowHeight = 66;
                Page.Section sec = p.section(null, false);
                String orderName = look.recentFirst ? s(R.string.st_order_recent) : s(R.string.st_order_custom);
                sec.add(new Page.Opt(s(R.string.st_home_order) + ": " + orderName, () -> {
                    if (look.setRecentFirst(!look.recentFirst)) { rebuildHome(); refreshPage(); }
                    else host.toast(s(R.string.st_save_failed));
                }));
                for (HomeItem item : homeItems) {
                    final String key = item.key;
                    Page.Opt o = sec.add(new Page.Opt(item.label, () -> { editKey = key; push(S_APPEDIT); }));
                    o.forward = true;
                }
                if (homeItems.isEmpty()) {
                    Page.Opt none = sec.add(new Page.Opt(s(R.string.st_home_empty), null));
                    none.enabled = false;
                }
                p.hint = s(R.string.st_hint_select);
                break;
            }
            case S_APPEDIT: {
                final HomeItem item = editKey == null ? null : byKey.get(editKey);
                p = new Page(screen, s(R.string.st_customize), item != null ? item.label : "", false);
                p.width = 1056;
                p.top = 260;
                Page.Section sec = p.section(null, false);
                if (item != null) {
                    final String key = item.key;
                    Page.Opt rename = sec.add(new Page.Opt(s(R.string.st_rename), () -> host.editLabel(key, item.label)));
                    rename.description = item.label;
                    Page.Opt unname = sec.add(new Page.Opt(s(R.string.st_rename_reset), () -> {
                        if (look.setCustomLabel(key, "")) host.catalogChanged();
                    }));
                    unname.enabled = look.customLabel(key) != null;
                    String mk = look.customMark(key);
                    Page.Opt icon = sec.add(new Page.Opt(s(R.string.st_icon_change), () -> host.chooseMark(key)));
                    icon.description = mk != null ? mk : s(R.string.st_icon_default);
                    Page.Opt unicon = sec.add(new Page.Opt(s(R.string.st_icon_reset), () -> {
                        if (look.setCustomMark(key, "")) host.catalogChanged();
                    }));
                    unicon.enabled = mk != null;
                }
                p.hint = s(R.string.st_hint_apply);
                break;
            }
            case S_SEARCH: {
                p = new Page(screen, s(R.string.menu_title), s(R.string.st_search_title), false);
                p.width = 1056;
                p.top = 212;
                p.rowHeight = 72;
                Page.Section sec = p.section(null, false);
                sec.add(new Page.Opt(s(R.string.st_search_new), host::searchApps));
                String q = searchQuery.toLowerCase();
                int shown = 0;
                for (HomeItem item : catalog) {
                    if (shown >= 7) break;
                    if (!item.label.toLowerCase().contains(q)) continue;
                    final HomeItem hit = item;
                    sec.add(new Page.Opt(item.label, () -> host.launch(hit)));
                    shown++;
                }
                if (shown == 0) {
                    Page.Opt none = sec.add(new Page.Opt(s(R.string.st_search_none), null));
                    none.enabled = false;
                }
                p.hint = s(R.string.st_hint_select);
                break;
            }
            case S_GREETING: {
                p = new Page(screen, s(R.string.st_group_tv), s(R.string.st_greeting), false);
                p.width = 1056;
                p.top = 260;
                p.rowHeight = 88;
                Page.Section sec = p.section(null, false);
                Page.Opt on = sec.add(new Page.Opt(s(R.string.st_greeting_on), () -> {
                    if (look.setGreetingOn(true)) { host.catalogChanged(); refreshPage(); }
                    else host.toast(s(R.string.st_save_failed));
                }));
                on.checked = look.greetingOn();
                Page.Opt off = sec.add(new Page.Opt(s(R.string.st_greeting_off), () -> {
                    if (look.setGreetingOn(false)) { host.catalogChanged(); refreshPage(); }
                    else host.toast(s(R.string.st_save_failed));
                }));
                off.checked = !look.greetingOn();
                Page.Section nm = p.section(s(R.string.st_name_section), false);
                Page.Opt name = nm.add(new Page.Opt(s(R.string.st_name_edit), host::editName));
                name.description = look.userName().isEmpty() ? s(R.string.st_name_none) : look.userName();
                Page.Opt birth = nm.add(new Page.Opt(s(R.string.st_birthday), host::editBirthday));
                birth.description = look.birthdaySet()
                        ? String.format(java.util.Locale.ROOT, "%02d.%02d.", look.birthDay, look.birthMonth)
                        : s(R.string.st_birthday_none);
                p.hint = s(R.string.st_hint_apply);
                break;
            }
            default:
                p = new Page(screen, "", "", false);
        }
        return p;
    }

    /** Saves one appearance axis; storage failure keeps the previous look. */
    private boolean apply(String key, String value) {
        String before = currentValue(key);
        if (value.equals(before)) return true;
        if (!look.set(key, value)) {
            host.toast(s(R.string.st_save_failed));
            return false;
        }
        if ("layout".equals(key) || "bg".equals(key) || "apps".equals(key)) {
            backdropKey = null;
            syncHomeScroll(false);
        }
        if ("bg".equals(key)) restartRotation();
        refreshPage();
        return true;
    }

    private String currentValue(String key) {
        switch (key) {
            case "bg": return look.bg;
            case "layout": return look.layout;
            case "apps": return look.apps;
            case "controls": return look.controls;
            case "focus": return look.focus;
            case "material": return look.material;
            case "font": return look.font;
            case "rotation": return look.rotation;
            case "wallpaper": return look.wallpaper;
            default: return null;
        }
    }

    private void refreshPage() {
        if (isPage(top())) {
            page = buildPage(top());
            fixPageFocus();
        }
        invalidate();
    }

    private void assignPs4(String inputId) {
        String old = look.ps4InputId();
        if (inputId == null ? old == null : inputId.equals(old)) return;
        if (!look.setPs4InputId(inputId)) { host.toast(s(R.string.st_save_failed)); return; }
        List<String> keys = look.homeKeys();
        String oldKey = old == null ? null : Look.INPUT_PREFIX + old;
        String newKey = inputId == null ? null : Look.INPUT_PREFIX + inputId;
        int at = oldKey == null ? -1 : keys.indexOf(oldKey);
        if (at >= 0) {
            if (newKey != null) keys.set(at, newKey); else keys.remove(at);
        } else if (newKey != null && keys.size() < Look.HOME_MAX) {
            keys.add(newKey);
        }
        if (keys.isEmpty()) keys.add(oldKey);
        look.saveHomeKeys(keys);
        host.catalogChanged();
        refreshPage();
    }

    private int firstEnabledRow(Page p) {
        List<Page.Row> rows = p.rows();
        for (int i = 0; i < rows.size(); i++) if (rowEnabled(rows.get(i))) return i;
        return -1;
    }

    private static boolean rowEnabled(Page.Row row) {
        if (row.index >= 0) return row.section.opts.get(row.index).enabled;
        for (Page.Opt o : row.section.opts) if (o.enabled) return true;
        return false;
    }

    private void fixPageFocus() {
        int[] pf = pageFocus.get(top());
        if (pf == null || page == null) return;
        List<Page.Row> rows = page.rows();
        if (pf[0] >= rows.size()) pf[0] = rows.size() - 1;
        if (pf[0] >= 0 && !rowEnabled(rows.get(pf[0]))) {
            int next = -1;
            for (int i = pf[0]; i < rows.size(); i++) if (rowEnabled(rows.get(i))) { next = i; break; }
            if (next < 0) for (int i = pf[0]; i >= 0; i--) if (rowEnabled(rows.get(i))) { next = i; break; }
            pf[0] = next;
        }
        if (pf[0] >= 0) {
            Page.Row row = rows.get(pf[0]);
            if (row.index < 0) {
                List<Page.Opt> opts = row.section.opts;
                pf[1] = HomePainter.clampInt(pf[1], 0, opts.size() - 1);
                if (!opts.get(pf[1]).enabled) for (int i = 0; i < opts.size(); i++) if (opts.get(i).enabled) { pf[1] = i; break; }
            }
        }
    }

    // ------------------------------------------------------------------ wallpaper gallery

    private void enterWallpaper() {
        wallFocus = Walls.indexOf(look.wallpaper);
        wallBack = false;
        int first = HomePainter.clampInt(wallFocus - 2, 0, Math.max(0, Walls.ALL.length - 6));
        wallFirst.jump(first);
        candidate = null;
        candidateBackdrop = null;
        applyWhenLoaded = false;
    }

    private void moveWallpaper(int delta) {
        int next = HomePainter.clampInt(wallFocus + delta, 0, Walls.ALL.length - 1);
        if (next == wallFocus) return;
        wallFocus = next;
        applyWhenLoaded = false;
        float first = wallFirst.target();
        if (wallFocus < first) first = wallFocus;
        if (wallFocus > first + 5) first = wallFocus - 5;
        wallFirst.to(first, dur(160));
        final int token = ++wallToken;
        final Walls.Wall wall = Walls.ALL[wallFocus];
        if (wall.id.equals(look.wallpaper)) {
            candidate = null;
            candidateBackdrop = null;
            invalidate();
            return;
        }
        final Context context = getContext().getApplicationContext();
        postDelayed(() -> {
            if (token != wallToken) return;
            host.work(() -> {
                Scene loaded = Scene.load(context, wall, true);
                post(() -> {
                    if (token != wallToken || loaded == null) return;
                    candidate = loaded;
                    candidateBackdrop = homePainter.composeBackdrop(loaded, look);
                    if (applyWhenLoaded) applyCandidate();
                    invalidate();
                });
            });
        }, WALL_DELAY);
        invalidate();
    }

    private void applyCandidate() {
        Walls.Wall wall = Walls.ALL[wallFocus];
        if (wall.id.equals(look.wallpaper)) return;
        if (candidate == null || !candidate.wall.id.equals(wall.id)) {
            applyWhenLoaded = true;
            return;
        }
        applyWhenLoaded = false;
        if (!look.set("wallpaper", wall.id)) { host.toast(s(R.string.st_save_failed)); return; }
        // Show the preview-resolution scene immediately, then swap in the full picture.
        scene = candidate;
        backdrop = candidateBackdrop;
        backdropKey = scene.wall.id + "|" + look.layout + "|" + System.identityHashCode(scene);
        candidate = null;
        candidateBackdrop = null;
        restartRotation();
        loadScene(wall.id, null);
        invalidate();
    }

    // ------------------------------------------------------------------ All apps

    private void scrollAll(boolean animate) {
        int row = allFocus / SheetPainter.GRID_COLS;
        if (row < allFirstRow) allFirstRow = row;
        if (row > allFirstRow + 2) allFirstRow = row - 2;
        if (animate) allFirst.to(allFirstRow, dur(220)); else allFirst.jump(allFirstRow);
    }

    private void toggleOnHome(HomeItem item) {
        List<String> keys = look.homeKeys();
        if (keys.contains(item.key)) {
            if (keys.size() <= 1) { host.toast(s(R.string.st_home_last)); return; }
            host.confirm(s(R.string.st_confirm_remove, item.label), () -> {
                List<String> now = look.homeKeys();
                now.remove(item.key);
                if (!look.saveHomeKeys(now)) { host.toast(s(R.string.st_save_failed)); return; }
                rebuildHome();
                invalidate();
            });
            return;
        } else {
            if (keys.size() >= Look.HOME_MAX) { host.toast(s(R.string.st_home_full)); return; }
            keys.add(item.key);
        }
        if (!look.saveHomeKeys(keys)) { host.toast(s(R.string.st_save_failed)); return; }
        rebuildHome();
        invalidate();
    }

    // ------------------------------------------------------------------ editor

    private List<HomeItem> editorRight() {
        Set<String> on = homeKeySet();
        List<HomeItem> right = new ArrayList<>();
        for (HomeItem item : catalog) if (!on.contains(item.key)) right.add(item);
        return right;
    }

    private void enterEditor(String focusKey) {
        edLifted = false;
        edMessage = null;
        edZone = homeItems.isEmpty() ? ED_RIGHT : ED_LEFT;
        edLeft = 0;
        if (focusKey != null) for (int i = 0; i < homeItems.size(); i++) if (homeItems.get(i).key.equals(focusKey)) edLeft = i;
        edRight = 0;
        edRightFirstRow = 0;
        edRightFirst.jump(0);
    }

    private boolean saveOrder(List<HomeItem> items) {
        List<String> keys = new ArrayList<>();
        for (HomeItem item : items) keys.add(item.key);
        // Keep stored keys of apps that are temporarily missing at the end.
        for (String key : look.homeKeys()) if (!keys.contains(key) && !byKey.containsKey(key)) keys.add(key);
        return look.saveHomeKeys(keys);
    }

    private void cancelLift() {
        if (edBeforeLift != null) look.saveHomeKeys(edBeforeLift);
        edLifted = false;
        edBeforeLift = null;
        rebuildHome();
    }

    private void scrollEditorRight(int size) {
        if (edRight < edRightFirstRow) edRightFirstRow = edRight;
        if (edRight > edRightFirstRow + 8) edRightFirstRow = edRight - 8;
        edRightFirstRow = HomePainter.clampInt(edRightFirstRow, 0, Math.max(0, size - 9));
        edRightFirst.to(edRightFirstRow, dur(180));
    }

    private void editorKey(int key) {
        List<HomeItem> right = editorRight();
        int n = homeItems.size();
        switch (edZone) {
            case ED_HEAD:
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) edZone = n > 0 ? ED_LEFT : ED_RIGHT;
                else if (key == KeyEvent.KEYCODE_DPAD_CENTER) pop();
                break;
            case ED_LEFT:
                if (edLifted) {
                    if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                        int to = edLeft + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                        if (to >= 0 && to < n) {
                            List<HomeItem> items = new ArrayList<>(homeItems);
                            HomeItem moving = items.remove(edLeft);
                            items.add(to, moving);
                            homeItems = items;
                            edLeft = to;
                        }
                    } else if (key == KeyEvent.KEYCODE_DPAD_CENTER) {
                        if (saveOrder(homeItems)) {
                            edLifted = false;
                            edBeforeLift = null;
                            edMessage = null;
                            rebuildHome();
                        } else {
                            edMessage = s(R.string.st_save_failed);
                            edMessageWarn = true;
                        }
                    }
                    break;
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP) { if (edLeft > 0) edLeft--; else edZone = ED_HEAD; }
                else if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (edLeft < n - 1) edLeft++; }
                else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) edZone = ED_REMOVE;
                else if (key == KeyEvent.KEYCODE_DPAD_CENTER && n > 1) {
                    edBeforeLift = look.homeKeys();
                    edLifted = true;
                    edMessage = s(R.string.st_editor_lifted);
                    edMessageWarn = false;
                }
                break;
            case ED_REMOVE:
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) edZone = ED_LEFT;
                else if (key == KeyEvent.KEYCODE_DPAD_RIGHT && !right.isEmpty()) {
                    edZone = ED_RIGHT;
                    edRight = HomePainter.clampInt(edLeft + edRightFirstRow, 0, right.size() - 1);
                    scrollEditorRight(right.size());
                } else if (key == KeyEvent.KEYCODE_DPAD_UP && edLeft > 0) edLeft--;
                else if (key == KeyEvent.KEYCODE_DPAD_DOWN && edLeft < n - 1) edLeft++;
                else if (key == KeyEvent.KEYCODE_DPAD_CENTER) {
                    if (n <= 1) { edMessage = s(R.string.st_home_last); edMessageWarn = true; break; }
                    final HomeItem leaving = homeItems.get(edLeft);
                    host.confirm(s(R.string.st_confirm_remove, leaving.label), () -> {
                        List<HomeItem> items = new ArrayList<>(homeItems);
                        items.remove(leaving);
                        if (saveOrder(items)) {
                            rebuildHome();
                            edLeft = Math.max(0, Math.min(edLeft, homeItems.size() - 1));
                            edMessage = null;
                            invalidate();
                        }
                    });
                }
                break;
            case ED_RIGHT:
                if (key == KeyEvent.KEYCODE_DPAD_UP) { if (edRight > 0) edRight--; else edZone = ED_HEAD; }
                else if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (edRight < right.size() - 1) edRight++; }
                else if (key == KeyEvent.KEYCODE_DPAD_LEFT && n > 0) {
                    edZone = ED_LEFT;
                    edLeft = HomePainter.clampInt(edRight - edRightFirstRow, 0, n - 1);
                } else if (key == KeyEvent.KEYCODE_DPAD_CENTER && !right.isEmpty()) {
                    if (n >= Look.HOME_MAX) { edMessage = s(R.string.st_home_full); edMessageWarn = true; break; }
                    List<HomeItem> items = new ArrayList<>(homeItems);
                    items.add(right.get(edRight));
                    if (saveOrder(items)) {
                        rebuildHome();
                        edMessage = null;
                        List<HomeItem> after = editorRight();
                        if (after.isEmpty()) { edZone = ED_LEFT; edLeft = homeItems.size() - 1; }
                        else edRight = Math.min(edRight, after.size() - 1);
                    }
                }
                scrollEditorRight(editorRight().size());
                break;
            default:
                break;
        }
        invalidate();
    }

    // ------------------------------------------------------------------ keys

    private static int normalize(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                return KeyEvent.KEYCODE_DPAD_CENTER;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                return KeyEvent.KEYCODE_BACK;
            default:
                return keyCode;
        }
    }

    private static boolean handled(int key) {
        return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_LEFT
                || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_BACK
                || key == KeyEvent.KEYCODE_MENU;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = normalize(event.getKeyCode());
        lastInput = SystemClock.uptimeMillis();
        if (saverOn) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) { swallowKey = event.getKeyCode(); stopSaver(); }
            return true;
        }
        if (swallowKey == event.getKeyCode()) {
            if (event.getAction() == KeyEvent.ACTION_UP) swallowKey = -1;
            return true;
        }
        if (look.sound && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_CENTER)) {
            playSoundEffect(android.view.SoundEffectConstants.CLICK);
        }
        if (!handled(key)) return super.dispatchKeyEvent(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_BACK) {
            // OK and Back act once per press; a held OK becomes the long action.
            if (down) {
                if (event.getRepeatCount() == 0) {
                    heldKey = key;
                    longFired = false;
                } else if (key == KeyEvent.KEYCODE_DPAD_CENTER && heldKey == key && !longFired && event.getRepeatCount() >= 2) {
                    longFired = true;
                    onLong();
                }
                return true;
            }
            if (heldKey != key) return true;
            heldKey = -1;
            if (longFired) return true;
            if (key == KeyEvent.KEYCODE_BACK) onBack(); else onKey(KeyEvent.KEYCODE_DPAD_CENTER);
            return true;
        }
        if (!down) return true;
        if (key == KeyEvent.KEYCODE_MENU) {
            if (event.getRepeatCount() == 0) onLong();
            return true;
        }
        onKey(key);
        return true;
    }

    private void onBack() {
        int screen = top();
        if (screen == S_HOME) {
            if (homeZone == HomePainter.Z_UTIL && !homeItems.isEmpty()) {
                homeZone = HomePainter.Z_APPS;
                invalidate();
            } else if (!host.homeVariant()) {
                host.leave();
            }
            return;
        }
        if (screen == S_EDITOR && edLifted) {
            cancelLift();
            edMessage = null;
            invalidate();
            return;
        }
        pop();
    }

    /** Held OK or the Menu key. */
    private void onLong() {
        int screen = top();
        if (screen == S_HOME && homeZone == HomePainter.Z_APPS && !homeItems.isEmpty()) {
            String focusKey = homeItems.get(homeIndex).key;
            push(S_EDITOR);
            enterEditor(focusKey);
            invalidate();
        } else if (screen == S_ALL && !allHead && !catalog.isEmpty()) {
            toggleOnHome(catalog.get(allFocus));
        }
    }

    private void onKey(int key) {
        switch (top()) {
            case S_HOME: homeKey(key); break;
            case S_SETTINGS: settingsKey(key); break;
            case S_WALLPAPER: wallpaperKey(key); break;
            case S_ALL: allKey(key); break;
            case S_EDITOR: editorKey(key); break;
            default: pageKey(key); break;
        }
    }

    private void homeKey(int key) {
        int n = homeItems.size();
        boolean dock = look.dock();
        if (homeZone == HomePainter.Z_APPS && n > 0) {
            int prev = dock ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_UP;
            int next = dock ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_DOWN;
            int toUtil = dock ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_RIGHT;
            if (key == prev && homeIndex > 0) { homeIndex--; syncHomeScroll(true); }
            else if (key == next && homeIndex < n - 1) { homeIndex++; syncHomeScroll(true); }
            else if (key == toUtil) { homeZone = HomePainter.Z_UTIL; lens.jump(util); }
            else if (key == KeyEvent.KEYCODE_DPAD_CENTER) host.launch(homeItems.get(homeIndex));
        } else {
            homeZone = HomePainter.Z_UTIL;
            int prev = dock ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_UP;
            int next = dock ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_DOWN;
            int toApps = dock ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_LEFT;
            if (key == prev && util > 0) { util--; lens.to(util, dur(140)); }
            else if (key == next && util < utilKinds().length - 1) { util++; lens.to(util, dur(140)); }
            else if (key == toApps && n > 0) homeZone = HomePainter.Z_APPS;
            else if (key == KeyEvent.KEYCODE_DPAD_CENTER) openUtil();
        }
        invalidate();
    }

    private void settingsKey(int key) {
        if (key == KeyEvent.KEYCODE_DPAD_UP && groupFocus > 0) groupFocus--;
        else if (key == KeyEvent.KEYCODE_DPAD_DOWN && groupFocus < GROUPS - 1) groupFocus++;
        else if (key == KeyEvent.KEYCODE_DPAD_CENTER) {
            int[] screens = {S_BACKGROUND, S_LAYOUT, S_TYPE, S_FOCUS, S_EDITOR, S_TV};
            push(screens[groupFocus]);
        }
        invalidate();
    }

    private void pageKey(int key) {
        if (page == null) return;
        int[] pf = pageFocus.get(top());
        List<Page.Row> rows = page.rows();
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            int r = pf[0] - 1;
            while (r >= 0 && !rowEnabled(rows.get(r))) r--;
            pf[0] = Math.max(-1, r);
            enterRow(pf, rows);
        } else if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            int r = pf[0] + 1;
            while (r < rows.size() && !rowEnabled(rows.get(r))) r++;
            if (r < rows.size()) { pf[0] = r; enterRow(pf, rows); }
        } else if ((key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) && pf[0] >= 0) {
            Page.Row row = rows.get(pf[0]);
            if (row.index < 0) {
                int d = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                int c = pf[1] + d;
                while (c >= 0 && c < row.section.opts.size() && !row.section.opts.get(c).enabled) c += d;
                if (c >= 0 && c < row.section.opts.size()) pf[1] = c;
            }
        } else if (key == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (pf[0] < 0) { pop(); return; }
            Page.Row row = rows.get(pf[0]);
            Page.Opt opt = row.index >= 0 ? row.section.opts.get(row.index) : row.section.opts.get(pf[1]);
            if (opt.enabled && opt.action != null) opt.action.run();
        }
        invalidate();
    }

    private void enterRow(int[] pf, List<Page.Row> rows) {
        if (pf[0] < 0) return;
        Page.Row row = rows.get(pf[0]);
        if (row.index >= 0) return;
        for (int i = 0; i < row.section.opts.size(); i++) if (row.section.opts.get(i).checked && row.section.opts.get(i).enabled) { pf[1] = i; return; }
        pf[1] = 0;
    }

    private void wallpaperKey(int key) {
        if (wallBack) {
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) wallBack = false;
            else if (key == KeyEvent.KEYCODE_DPAD_CENTER) pop();
            invalidate();
            return;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (wallFocus == 0) { wallBack = true; invalidate(); } else moveWallpaper(-1);
        } else if (key == KeyEvent.KEYCODE_DPAD_DOWN) moveWallpaper(1);
        else if (key == KeyEvent.KEYCODE_DPAD_CENTER) applyCandidate();
        invalidate();
    }

    private void allKey(int key) {
        int n = catalog.size();
        int cols = SheetPainter.GRID_COLS;
        if (allHead) {
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && n > 0) allHead = false;
            else if (key == KeyEvent.KEYCODE_DPAD_CENTER) push(S_EDITOR);
            invalidate();
            return;
        }
        int col = allFocus % cols;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT && col > 0) allFocus--;
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT && col < cols - 1 && allFocus < n - 1) allFocus++;
        else if (key == KeyEvent.KEYCODE_DPAD_UP) { if (allFocus >= cols) allFocus -= cols; else allHead = true; }
        else if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (allFocus + cols < n) allFocus += cols;
            else if (allFocus / cols < (n - 1) / cols) allFocus = n - 1;
        } else if (key == KeyEvent.KEYCODE_DPAD_CENTER && n > 0) host.launch(catalog.get(allFocus));
        scrollAll(true);
        invalidate();
    }

    /** Kept for the activity: the inputs map changed (HDMI labels or PS4). */
    public void inputsChanged() {
        if (isPage(top())) refreshPage();
    }

    /** For tests and the activity: a readable summary of where focus is. */
    public String where() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("screen", top());
        m.put("homeIndex", homeIndex);
        m.put("zone", homeZone);
        return m.toString();
    }
}
