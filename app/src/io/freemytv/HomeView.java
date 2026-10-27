package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps.
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, LAUNCH = 8, CONTROL = 9;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) { return (id - 1) / 1000 == GRID && canToggleShelf((id - 1) % 1000); }
        @Override public void hold(int id) { a11yHold(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // The three tabs, then the round buttons at the top right (up to HomeApps.MAX_TOP).
    private final Spring[] topFocus = new Spring[3 + HomeApps.MAX_TOP];
    private final RectF[] topRects = new RectF[3 + HomeApps.MAX_TOP];
    private final Spring[] cardFocus = new Spring[3];
    private final RectF[] cardRects = new RectF[3];

    // Cinematic layout (without the picks): Continue watching above the shelf (the first card bigger), the big clock
    // and the date above it, the weather as a chip by the top buttons; dark glass over the photo's darkened bottom.
    // Like Apple TV's Up Next: equal 16:9 cards with the title under them, a heading above.
    private static final int Z_ROW = 6, ROW_MAX = 4;
    /** The Cricket row (under Continue watching; the page scrolls up to it) and its cards' size. */
    private static final int Z_CRICKET = 7, CRICKET_MAX = 12;
    private static final float C_CK_W = 340, C_CK_H = 128, C_CK_GAP = 24, C_CK_BELOW_ROW = 300;
    // Apple TV's order: the apps under the clock, Continue watching under the apps.
    private static final float C_SHELF_TOP = 596, C_ROW_TOP = 882, C_CARD_W = 258, C_ROW_GAP = 36, C_HEAD_BASE = 846;
    // The clock fills the space between the tabs and the apps: 185 px digits, the date under them.
    // Samsung-style clock: hours over minutes, small rounded digits in the wallpaper's color.
    private static final float C_CLOCK_SIZE = 132, C_GREET_BASE = 184;
    private static final float C_CHIP_H = 56, C_RADIUS = 18;
    private final Spring[] rowFocus = new Spring[ROW_MAX];
    private final RectF[] rowRects = new RectF[ROW_MAX];
    private final String[] rowTitles = new String[ROW_MAX], rowMetas = new String[ROW_MAX];
    private List<History.Entry> rowItems = new ArrayList<>();
    private final Map<String, Bitmap> rowArt = new HashMap<>();
    private int rowIndex;
    private long rowLoadedAt;
    private TextPaint cDate, cChip, cRowTitle, cRowMeta, cRowHead;
    private Paint cClock, cAmPm;
    private TextPaint cGreet;
    private int accentFrom = 0xFFFFFFFF, accentTo = 0xFFFFFFFF;
    private String greeting = "";
    /** Today's effect over the wallpaper (Celebrate: snow, sparks, confetti...); NONE most days. */
    private int festiveEffect = Celebrate.NONE;
    private String reminderLine = "";
    private final Paint festivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long bgFadeMs = FADE_MS;   // a photo takes its time
    private int driftTick;
    private String amPm = "";   // "PM" beside a 12-hour clock; empty with a 24-hour one
    // The tab bar: a smoked capsule at the top center; a white pill slides to the focused tab (tabPos: 0 to 2).
    private final RectF tabBar = new RectF(), pillRect = new RectF();
    private final Spring tabPos = new Spring(0.32f, 0.82f, 0);
    private final Paint shine = new Paint(Paint.DITHER_FLAG);
    // Vision Pro glass: the focused tile (and Continue watching card) tilts in 3D toward the last move and settles
    // with a little sway; the shelf tile also floats gently while it has focus. A light edge runs round its top and a
    // glint slides across it with the tilt. The wallpaper and the clock layer drift apart with the focus (parallax).
    private final Spring tilt = new Spring(0.55f, 0.5f, 0);       // degrees around the vertical axis, kicked by ← →
    private final Spring parallax = new Spring(0.9f, 1f, 0);      // -1 (focus at the left) to 1 (at the right)
    private final Spring tiltV = new Spring(0.55f, 0.5f, 0);      // All Apps: degrees around the horizontal axis, kicked by ↑ ↓
    /** Depth of field: 1 = the wallpaper photo out of focus (Continue watching has the focus), 0 = sharp. */
    private final Spring depthBlur = new Spring(0.7f, 1f, 0);
    private final Paint softPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint pane = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF paneShadow = new RectF();
    // The Cricket row: its cards, the focus, the row's sideways shift to keep the focused card on screen, and the whole
    // page scrolling up to reveal it.
    private final Spring[] cricketFocus = new Spring[CRICKET_MAX];
    private final RectF[] cricketRects = new RectF[CRICKET_MAX];
    private final Spring cricketShift = new Spring(0.45f, 1f, 0), pageScroll = new Spring(0.55f, 1f, 0);
    private final RectF flagRect = new RectF();
    private boolean pageScrolled;
    private int cricketIndex;
    private TextPaint cCkTeam, cCkMeta, cCkLive, cCkTitle, cCkStatus, cNext;
    // Control Center (the top-right button): the panel on its own layer.
    private ControlCenter controlCenter;
    private Layer controlLayer;
    private boolean controlDirty;
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path iconPath = new android.graphics.Path();
    {
        for (int i = 0; i < CRICKET_MAX; i++) {
            cricketFocus[i] = new Spring(0.32f, 0.78f, 0);
            cricketRects[i] = new RectF();
        }
    }
    // Opening an app: the selected tile grows smoothly to fill the screen (LAUNCH_MS), then the app opens over it with
    // a fade; back on the home screen, the full-screen card fades away.
    private static final long LAUNCH_MS = 420, UNLAUNCH_MS = 300;
    private Layer launchLayer;
    private HomeApps.Item launchItem;
    private final RectF launchFrom = new RectF(), launchRect = new RectF(), launchArt = new RectF();
    private long launchAt, launchFadeAt;
    private boolean launchStarted;
    private LinearGradient launchFill;
    private final Paint launchPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    /** All Apps: how much the panes around the selected one are dimmed (follows the focus). */
    private final Spring gridDim = new Spring(0.3f, 1f, 0);
    private final android.graphics.Camera camera3d = new android.graphics.Camera();
    private final android.graphics.Matrix depth = new android.graphics.Matrix(), unitMatrix = new android.graphics.Matrix();
    private final Paint rimLight = new Paint(Paint.ANTI_ALIAS_FLAG), glint = new Paint(Paint.DITHER_FLAG);
    private TextPaint tileName;
    private final RectF shelfOpenFrom = new RectF();
    private static final float TILT_KICK = 95, PARALLAX_PX = 10, CLOCK_PARALLAX_PX = 6;
    private final android.graphics.Matrix shineMatrix = new android.graphics.Matrix();
    private final Paint rowScrim = new Paint(Paint.DITHER_FLAG);
    private final android.graphics.Matrix rowScrimMatrix = new android.graphics.Matrix();
    private final RectF chipRect = new RectF();
    private String chipText = "", chipSymbol = "";
    private long chipWeatherAt = -1;
    private int chipPrefs = -1;
    private String chipLanguage = "";
    private Cricket cricket;
    private int cricketVersion = -1;
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    /** An app's tile colours (top, bottom, logo mode), or null: the phone's Home draws the same tiles (PhoneHome). */
    static int[] tileStyle(String key) {
        return TILE_STYLE.get(key);
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case LAUNCH: drawLaunch(c); break;
                case CONTROL: controlCenter.draw(c, k, w); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            if (SystemClock.uptimeMillis() - rowLoadedAt > 2 * 60_000L) loadRow();
            Telegram.syncIfDue(getContext());   // new wallpapers from your channel, every 10 minutes
            if (cinematic()) {
                cricket.refreshIfStale();
                if (cricket.version != cricketVersion) heroDirty = statusDirty = true;
            }
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        cricket = Cricket.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        launchLayer = new Layer(a, LAUNCH);
        controlLayer = new Layer(a, CONTROL);
        controlCenter = new ControlCenter(a, () -> {
            controlDirty = true;
            wake();
        }, () -> { });
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, launchLayer, controlLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription(Ui.BRAND);
        drawerLayer.setVisibility(INVISIBLE);
        launchLayer.setVisibility(INVISIBLE);
        controlLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < 3; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        for (int i = 0; i < ROW_MAX; i++) {
            rowFocus[i] = new Spring(0.32f, 0.78f, 0);
            rowRects[i] = new RectF();
        }
        // The cards' bottom scrim: one shader in unit space, placed on each card by its matrix (nothing made in onDraw).
        rowScrim.setShader(new LinearGradient(0, 0, 0, 1, new int[] {0x00000000, 0x99000000, 0xE0000000},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        setLightButtons(HomeApps.topButtons());
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        if (launchItem != null && launchFadeAt == 0) launchFadeAt = SystemClock.uptimeMillis();   // back from the app
        checkNight(false);
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        if (!again) {
            final Context app = getContext().getApplicationContext();
            LOADER.execute(() -> History.ensureAccess(app));   // play history access, once ADB is approved
        }
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        if (!again) reload();
        if (!again) loadRow();
        if (!again) Telegram.syncIfDue(getContext());
        updatePalette();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        controlCenter.close();
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        lightButtons.clear();
        lightButtons.addAll(list);
        if (zone == Z_TOP && topIndex >= 3 + lightButtons.size()) {
            topIndex = 2 + lightButtons.size();
            if (topIndex < 3 && !picksOn()) zone = Z_SHELF;   // without the picks the tabs can't hold focus
            else if (topIndex < 3) selectTab(topIndex);
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private String[] tabs() {
        int generation = Ui.generation * 2 + (cinematic() ? 1 : 0);
        if (tabsGeneration != generation) {
            // Cinematic (without the picks): a launcher's sections. Apps opens All Apps, Settings the TV's settings.
            tabs = cinematic() ? new String[] {Ui.tr("tab|Home"), Ui.tr("tab|Apps"), Ui.tr("light|Settings")}
                    : new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows")};
            tabsGeneration = generation;
        }
        return tabs;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        // Cinematic: the row is under the shelf, so the top layer reaches the bottom of the screen.
        measure(heroLayer, width, cinematic() ? height + Math.round(440 * kk) : Math.round(790 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        measure(launchLayer, width, height);
        measure(controlLayer, width, height);
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        // The shelf's layers start 64 and 24 px above its glass (room for the shadow and the grown tiles).
        float shelf = cinematic() ? C_SHELF_TOP : L_PLATTER_TOP;
        place(platter, 0, Math.round((shelf - 64) * kk));
        place(tiles, 0, Math.round((shelf - 24) * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        launchLayer.layout(0, 0, width, height);
        controlLayer.layout(0, 0, width, height);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        cDate = new TextPaint(Ui.text(Ui.lato, 44 * k, 0xF2FFFFFF));
        cChip = new TextPaint(Ui.text(Ui.medium, 28 * k, 0xF2FFFFFF));
        cRowTitle = new TextPaint(Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF));
        cRowMeta = new TextPaint(Ui.text(Ui.regular, 22 * k, 0xA6FFFFFF));
        cRowHead = new TextPaint(Ui.text(Ui.semibold, 32 * k, 0xE6FFFFFF));
        cCkTeam = new TextPaint(Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF));
        cCkMeta = new TextPaint(Ui.text(Ui.regular, 20 * k, 0x99FFFFFF));
        cCkLive = new TextPaint(Ui.text(Ui.semibold, 20 * k, 0xFFFFFFFF));
        cCkTitle = new TextPaint(Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF));
        cCkStatus = new TextPaint(Ui.text(Ui.regular, 22 * k, 0x99FFFFFF));
        cNext = new TextPaint(Ui.text(Ui.semibold, 22 * k, 0xF2FFFFFF));
        cNext.setTextAlign(Paint.Align.RIGHT);
        cNext.setShadowLayer(8 * k, 0, 1 * k, 0x99000000);
        cClock = Ui.numbers(Ui.displayMedium, C_CLOCK_SIZE * k, 0xFFFFFFFF);
        // Filled and stroked with round joins: the digits' corners go soft, as on Samsung's lock screen.
        cClock.setStyle(Paint.Style.FILL_AND_STROKE);
        cClock.setStrokeJoin(Paint.Join.ROUND);
        cClock.setStrokeCap(Paint.Cap.ROUND);
        cClock.setStrokeWidth(C_CLOCK_SIZE * 0.05f * k);
        cAmPm = Ui.text(Ui.lato, 38 * k, 0xD9FFFFFF);
        cGreet = new TextPaint(Ui.text(Ui.semibold, 44 * k, 0xE6FFFFFF));
        cGreet.setShadowLayer(10 * k, 0, 2 * k, 0x80000000);
        cClock.setLetterSpacing(0f);
        // Text over a photo: a soft shadow keeps it readable over any bright patch.
        for (Paint p : new Paint[] {cDate, cRowTitle, cRowMeta, cRowHead, cClock, cAmPm}) p.setShadowLayer(10 * k, 0, 2 * k, 0x80000000);
        // The focus shine: a soft diagonal band of light across a focused card, in unit space (placed per card).
        tileName = new TextPaint(Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF));
        tileName.setTextAlign(Paint.Align.CENTER);
        tileName.setShadowLayer(10 * k, 0, 2 * k, 0xB3000000);
        rimLight.setStyle(Paint.Style.STROKE);
        rimLight.setStrokeWidth(1.6f * k);
        rimLight.setShader(new LinearGradient(0, 0, 0, 1, new int[] {0xB3FFFFFF, 0x26FFFFFF, 0x00FFFFFF, 0x1FFFFFFF},
                new float[] {0, 0.18f, 0.6f, 1}, Shader.TileMode.CLAMP));
        glint.setShader(new LinearGradient(0, 0, 1, 0, new int[] {0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        shine.setShader(new LinearGradient(0, 0, 1, 0, new int[] {0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt), rowMoving = step(rowFocus, dt);
        rowMoving |= tabPos.step(dt);
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving | rowMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading || (bgFading && cinematic())) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        boolean drifting = photoDrifting() || festiveMoving();
        // Vision Pro glass: the tilt springs back; the parallax follows the focus across the screen.
        boolean tiltMoving = tilt.step(dt);
        boolean controlMoving = controlCenter.step(dt);
        if (controlMoving || controlDirty) {
            controlLayer.setVisibility(controlCenter.visible() ? VISIBLE : INVISIBLE);
            controlLayer.invalidate();
            controlDirty = false;
            tiltMoving |= controlMoving;
        }
        if (step(cricketFocus, dt) | cricketShift.step(dt)) {
            heroLayer.invalidate();
            tiltMoving = true;
        }
        pageScroll.target = cinematic() && zone == Z_CRICKET && !sheetOpen ? cricketScroll() : 0;
        boolean scrolling = pageScroll.step(dt);
        if (scrolling) {
            updateMotion();   // the layers move (no redraw)
            tiltMoving = true;
            pageScrolled = true;
        } else if (pageScrolled) {   // settled: the shelf's glass shows the background where it ended up
            pageScrolled = false;
            platter.invalidate();
        }
        if (tickLaunch(up)) tiltMoving = true;   // keeps the frames coming
        if (gridDim.step(dt) && sheetOpen) sheetLayer.invalidate();
        gridDim.target = sheetOpen ? 1 : 0;
        parallax.target = cinematic() && !sheetOpen ? (focusX() / Math.max(1, w) - 0.5f) * 2 : 0;
        boolean parallaxMoving = parallax.step(dt);
        boolean floating = cinematic() && zone == Z_SHELF && !shelf.isEmpty() && !sheetOpen && !drawerOpen
                && drawer.value <= 0.001f && launchItem == null;   // still while an app opens: every frame for the zoom
        if (tiltMoving || (floating && (driftTick & 1) == 0)) tiles.invalidate();
        if (tiltMoving && zone == Z_ROW) heroLayer.invalidate();
        boolean tiltVMoving = tiltV.step(dt);
        if ((tiltMoving || tiltVMoving) && sheetOpen) sheetLayer.invalidate();
        tiltMoving |= tiltVMoving;
        // Depth of field: the wallpaper goes soft while Continue watching has the focus, like a camera refocusing.
        depthBlur.target = cinematic() && (zone == Z_ROW || zone == Z_CRICKET) && !sheetOpen ? 1 : 0;
        if (depthBlur.step(dt)) {
            tiltMoving = true;   // keeps the frames coming
            if (bgTo != null && bgTo.photo) sky.invalidate();
        }
        if (parallaxMoving) {
            heroLayer.setTranslationX(-parallax.value * CLOCK_PARALLAX_PX * k);
            if (bgTo != null && bgTo.photo) sky.invalidate();
        }
        if (floating && !drifting) driftTick++;
        if (drifting && (++driftTick & 1) == 0) sky.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (!picksOn()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty() || drifting
                || tiltMoving || parallaxMoving || floating;
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift - pageScroll.value * k;   // the drawer, and the page scrolled up to the Cricket row
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        String period = Photos.period();
        greeting = period.equals("morning") ? Ui.tr("Good morning") : period.equals("afternoon") ? Ui.tr("Good afternoon")
                : period.equals("evening") ? Ui.tr("Good evening") : Ui.tr("Good night");
        // Today's reminders, under the date ("Reminder: Pay electricity bill · 9:00 AM", and how many more).
        List<Celebrate.Day> reminders = Celebrate.remindersToday(getContext());
        reminderLine = reminders.isEmpty() ? "" : Ui.trf("Reminder: {text}", "text", reminders.get(0).name + " · "
                + Celebrate.time(reminders.get(0).minute)) + (reminders.size() > 1 ? "  +" + (reminders.size() - 1) : "");
        String special = Celebrate.greeting(getContext());   // a birthday or a festival
        if (special != null) greeting = special;
        int effect = Celebrate.effect(getContext());
        if (effect != festiveEffect) {
            festiveEffect = effect;
            if (sky != null) sky.invalidate();
        }
        // The language's clock is 12-hour (an "h" in its pattern): AM or PM beside the big digits.
        String pattern = Ui.tr("format|h:mm");
        amPm = pattern.indexOf('h') >= 0 && pattern.indexOf('a') < 0
                ? new java.text.SimpleDateFormat("a", Ui.locale()).format(now) : "";
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.hasPhotos() ? null : recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || bgEase(up) >= 0.5f) bgFrom = bgTo;
        accentFrom = accentNow(up);
        accentTo = b != null && b.photo ? b.accent : 0xFFFFFFFF;
        bgTo = b;
        bgFadeMs = b != null && b.photo ? 2600 : FADE_MS;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        // The cinematic layout is dark (white text over the photo's darkened bottom) unless the theme is set to Light.
        boolean want = data.isNight(System.currentTimeMillis()) || (cinematic() && !"light".equals(Prefs.theme()));
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = bgEase(up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    /** The wallpaper's own fade (2.6 s for your photos, 0.8 s for the rest). */
    private float bgEase(long up) {
        return fadeEase(bgFadeAt, up, bgFadeMs);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    /** The clock's color now: moving from the old wallpaper's to the new one's as the photos cross-fade. */
    private int accentNow(long up) {
        return mix(accentFrom, accentTo, bgFadeAt == 0 ? 1 : bgEase(up));
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched; never taller than L_TILE_H. The glass
        // keeps its bottom edge, so with more apps it only gets lower.
        float tw = Math.min(L_TILE_H * 16f / 9 * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float th = tw * 9f / 16;
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = ((cinematic() ? C_SHELF_TOP : L_PLATTER_TOP) + L_TILE_H) * k - th;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
        if (cinematic()) {   // Vision Pro glass: light catching the top edge of the pane
            unitMatrix.setScale(1, platterRect.height());
            unitMatrix.postTranslate(0, platterRect.top);
            rimLight.getShader().setLocalMatrix(unitMatrix);
            rimLight.setAlpha(Math.round(255 * tone(0.55f, 0.8f)));
            float inset = rimLight.getStrokeWidth() / 2;
            c.drawRoundRect(platterRect.left + inset, platterRect.top + inset, platterRect.right - inset,
                    platterRect.bottom - inset, radius, radius, rimLight);
        }
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = !picksOn();
        // At the right margin, under the round buttons: the shelf's glass is narrower and centred with few apps.
        float right = CONTENT_R * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = 3; i < 3 + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        // One read of the list, so n always matches the coins drawn below.
        int[] coins = HomeData.active();
        int n = coins.length;
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (i == top && cinematic() && liftIndex < 0) {
                    drawTile3d(c, i);
                    continue;
                }
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    dimTile(c, i, most);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
    }

    /** Cinematic home: the tiles beside the selected one step back (dimmed), so the selected one is plain to see. */
    private void dimTile(Canvas c, int i, float focusOfSelected) {
        if (!cinematic() || zone != Z_SHELF || focusOfSelected <= 0.02f) return;
        float dim = 0.45f * Ui.clamp(focusOfSelected) * (1 - Ui.clamp(tileFocus[i].value));
        if (dim <= 0.01f) return;
        RectF r = tileRects[i];
        float rr = TILE_RADIUS * k;
        tileFill.setShader(null);
        tileFill.setColor(Ui.alpha(0xFF000000, dim));
        c.drawRoundRect(r, rr, rr, tileFill);
        tileFill.setColor(0xFFFFFFFF);
    }

    /** The focused shelf tile, Vision Pro style: lifted, tilted in 3D (the last move's lean and a slow sway), lit. */
    private void drawTile3d(Canvas c, int i) {
        float f = Ui.clamp(tileFocus[i].value);
        float t = SystemClock.uptimeMillis() / 1000f;
        float ry = (tilt.value + 2.4f * (float) Math.sin(t * 0.9f)) * f;
        float rx = (1.5f * (float) Math.cos(t * 0.7f) - 2.5f) * f;   // leaning back a little, breathing
        RectF base = tileRects[i];
        c.save();
        c.translate(0, -6 * k * f);
        applyDepth(c, base.centerX(), base.centerY(), rx, ry);
        drawShelfTile(c, i);   // leaves its focused size in `cell`
        float rr = TILE_RADIUS * k * (1 + FOCUS_GROW * tileFocus[i].value);
        drawGlassLight(c, cell, rr, ry, f);
        // A clear white ring: which app is selected, at a glance from the sofa.
        rim.setShader(null);
        rim.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * f));
        rim.setStrokeWidth(3.5f * k);
        float o = 5 * k;
        c.drawRoundRect(cell.left - o, cell.top - o, cell.right + o, cell.bottom + o, rr + o, rr + o, rim);
        rim.setStrokeWidth(2.5f * k);
        c.restore();
        // Its name under the shelf, as on Apple TV: the logos alone don't always say which app it is.
        String name = shelf.get(i).label;
        if (name != null && !name.isEmpty() && f > 0.02f) {
            tileName.setAlpha(Math.round(255 * f));
            c.drawText(name, base.centerX(), platterRect.bottom + 42 * k, tileName);
        }
    }

    /** Turns what's drawn next around (cx, cy): rx and ry degrees around the horizontal and vertical axes. */
    private void applyDepth(Canvas c, float cx, float cy, float rx, float ry) {
        camera3d.save();
        camera3d.setLocation(0, 0, -16);   // far: a gentle perspective
        camera3d.rotateX(rx);
        camera3d.rotateY(ry);
        camera3d.getMatrix(depth);
        camera3d.restore();
        depth.preTranslate(-cx, -cy);
        depth.postTranslate(cx, cy);
        c.concat(depth);
    }

    /** The glass's light on a focused rectangle: a bright edge at the top and a glint that moves with the tilt. */
    private void drawGlassLight(Canvas c, RectF r, float radius, float ry, float amount) {
        if (amount <= 0.02f) return;
        int alpha = Math.round(255 * amount);
        unitMatrix.setScale(1, r.height());
        unitMatrix.postTranslate(0, r.top);
        rimLight.getShader().setLocalMatrix(unitMatrix);
        rimLight.setAlpha(alpha);
        float inset = rimLight.getStrokeWidth() / 2;
        c.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, radius, radius, rimLight);
        float band = r.width() * 0.5f, at = r.centerX() + r.width() * (ry / 14f) - band / 2;
        unitMatrix.setScale(band, 1);
        unitMatrix.postSkew(-0.45f, 0, 0, r.centerY());
        unitMatrix.postTranslate(at, 0);
        glint.getShader().setLocalMatrix(unitMatrix);
        glint.setAlpha(alpha);
        c.drawRoundRect(r, radius, radius, glint);
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
        }
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 8 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale);
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        heroChanged |= aim(rowFocus, !sheetOpen && zone == Z_ROW ? rowIndex : -1, snap);
        heroChanged |= aim(cricketFocus, !sheetOpen && zone == Z_CRICKET ? cricketIndex : -1, snap);
        // The focused card kept on screen: the row slides once it would pass the fourth card.
        cricketShift.target = zone == Z_CRICKET ? Math.max(0, cricketIndex - 3) * (C_CK_W + C_CK_GAP) * k : cricketShift.target;
        if (snap) cricketShift.snap(cricketShift.target);
        if (!sheetOpen && zone == Z_TOP && topIndex < 3) {
            // The pill slides between tabs; arriving from elsewhere, it starts at the tab (and fades in there).
            float shown = Math.max(topFocus[0].value, Math.max(topFocus[1].value, topFocus[2].value));
            if (snap || shown < 0.05f) tabPos.snap(topIndex); else tabPos.target = topIndex;
            heroChanged = true;
        }
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    /**
     * All Apps as floating glass (in the cinematic home): a frosted room (your photo, softly blurred and dimmed), each
     * app on its own pane of glass with a lit edge; the focused pane lifts, leans the way you moved (in 3D) and
     * catches a glint. Elsewhere, the dimmed grid as before.
     */
    private void drawSheet(Canvas c) {
        boolean glassy = cinematic();
        Backdrop b = bgTo;
        if (glassy && b != null && b.photo && b.soft != null) {
            softPaint.setAlpha(255);
            c.drawBitmap(b.soft, null, screen, softPaint);
            c.drawColor(0x99000000);
        } else {
            c.drawColor(0xA8000000);
        }
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        int top = -1;   // the focused one last: it lifts over its neighbours
        float most = 0.02f;
        for (int i = 0; i < n; i++) if (gridFocus[i].value > most) { most = gridFocus[i].value; top = i; }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                if ((pass == 0) == (i == top)) continue;
                int r = i / COLUMNS, col = i % COLUMNS;
                float y = gridTop + r * GRID_ROW * k - shift;
                if (y > h || y + GRID_ROW * k < 0) continue;
                float x = left + col * (GRID_W + GRID_GAP) * k;
                gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
                float f = gridFocus[i].value, fc = Ui.clamp(f);
                if (!glassy) {
                    drawCard(c, others.get(i).tile, gridCell, GRID_RADIUS * k, f, 0.08f);
                    if (sheetOpen && i == gridIndex) focusedCell.set(cell);
                    gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, fc));
                    c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
                    continue;
                }
                // The pane: the card and its name on glass, a little bigger than the card.
                float pad = 10 * k;
                tmp.set(gridCell.left - pad, gridCell.top - pad, gridCell.right + pad, gridCell.bottom + 54 * k);
                float cx = tmp.centerX(), cy = tmp.centerY(), s = 1 + 0.12f * f;
                c.save();
                if (fc > 0.02f) {
                    c.translate(0, -8 * k * fc);
                    applyDepth(c, cx, cy, tiltV.value * fc - 2f * fc, tilt.value * fc);
                }
                c.scale(s, s, cx, cy);
                float pr = (GRID_RADIUS + 10) * k;
                if (fc > 0.02f) {   // its shadow on the frosted room
                    paneShadow.set(tmp);
                    paneShadow.offset(0, 18 * k * fc);
                    shadow.setColor(Ui.alpha(0xFF000000, 0.5f * fc));
                    shadows.draw(c, paneShadow, pr, SHADOW_BLUR * k, shadow);
                }
                if (fc > 0.02f) {   // a soft white glow round the selected pane
                    halo.setColor(Ui.alpha(0xFFFFFFFF, 0.42f * fc));
                    c.drawRoundRect(tmp, pr, pr, halo);
                }
                pane.setShader(null);
                // The selected pane turns to bright glass; the others stay clear.
                pane.setColor(blend(Ui.alpha(0xFFFFFFFF, 0.07f), 0xF2F4F3F8, fc));
                c.drawRoundRect(tmp, pr, pr, pane);
                drawCard(c, others.get(i).tile, gridCell, GRID_RADIUS * k, 0, 0);
                gridLabel.setColor(blend(0xB3FFFFFF, 0xFF1C1B1F, fc));
                gridLabel.setFakeBoldText(fc > 0.5f);
                c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + 38 * k, gridLabel);
                gridLabel.setFakeBoldText(false);
                // The others step back: dimmed while something is selected.
                float dim = Ui.clamp(gridDim.value) * (1 - fc) * (most > 0.02f ? 0.5f : 0);
                if (dim > 0.01f) {
                    pane.setColor(Ui.alpha(0xFF000000, dim));
                    c.drawRoundRect(tmp, pr, pr, pane);
                }
                unitMatrix.setScale(1, tmp.height());
                unitMatrix.postTranslate(0, tmp.top);
                rimLight.getShader().setLocalMatrix(unitMatrix);
                rimLight.setAlpha(Math.round(255 * (0.35f + 0.65f * fc)));
                float inset = rimLight.getStrokeWidth() / 2;
                c.drawRoundRect(tmp.left + inset, tmp.top + inset, tmp.right - inset, tmp.bottom - inset, pr, pr, rimLight);
                if (fc > 0.02f) drawGlassLight(c, tmp, pr, tilt.value * fc, fc * 0.8f);
                c.restore();
                if (sheetOpen && i == gridIndex) {   // where it shows now, for the next move's transition
                    focusedCell.set(cx - tmp.width() * s / 2, cy - tmp.height() * s / 2, cx + tmp.width() * s / 2, cy + tmp.height() * s / 2);
                }
            }
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons() : null;   // they change with Prefs too
            handler.post(() -> {
                if (top != null) setLightButtons(top);
                if (s != null) setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        if (controlCenter.onKey(e)) return true;   // the panel is open: its keys
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf. A timer, since
                // not every remote repeats a held key, or the long-press repeat, whichever comes first; the release
                // after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : canReorder();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (dx != 0 && cinematic()) tilt.velocity += dx * TILT_KICK;   // the tile (or All Apps pane) leans the way it went
        if (dy != 0 && sheetOpen && cinematic()) tiltV.velocity -= dy * TILT_KICK * 0.7f;
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (!sheetOpen && zone == Z_CRICKET) {   // watch it: JioHotstar has the cricket in India
            HomeApps.Item hotstar = HomeApps.app("in.startv.hotstar", "JioHotstar");
            if (HomeApps.launchIntent(getContext().getPackageManager(), hotstar.pkg) == null) {
                showToast(Ui.tr("Install JioHotstar to watch the cricket."));
                return;
            }
            click.open();
            startLaunch(hotstar, cricketRects[cricketIndex]);
            return;
        }
        if (!sheetOpen && zone == Z_ROW) {
            if (rowIndex < rowItems.size()) {
                click.open();
                openEntry(rowItems.get(rowIndex));
            }
            return;
        }
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 3 && cinematic()) {
                openTab(topIndex);
                return;
            }
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = cardRects[cardIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            // From where the selected tile shows: grown and lifted (so the opening starts without a jump).
            RectF t = tileRects[shelfIndex];
            float gw = t.width() * (1 + FOCUS_GROW) / 2, gh = t.height() * (1 + FOCUS_GROW) / 2, lift = cinematic() ? 6 * k : 0;
            shelfOpenFrom.set(t.centerX() - gw, t.centerY() - gh - lift, t.centerX() + gw, t.centerY() + gh - lift);
            r = shelfOpenFrom;
        }
        click.open();
        if (it.kind == HomeApps.CONTROL) {
            controlCenter.open(r);
            return;
        }
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        startLaunch(it, r);
    }

    /** The opening animation: the selected item grows to fill the screen; the app opens when it has (in doFrame). */
    private void startLaunch(HomeApps.Item it, RectF from) {
        if (launchItem != null && !launchStarted) return;   // already opening one
        launchItem = it;
        launchFrom.set(from);
        launchAt = SystemClock.uptimeMillis();
        launchFadeAt = 0;
        launchStarted = false;
        int[] style = TILE_STYLE.get(it.key);
        launchFill = style == null ? null : new LinearGradient(0, 0, 0, 1, style[0], style[1], Shader.TileMode.CLAMP);
        launchLayer.setAlpha(1);
        launchLayer.setVisibility(VISIBLE);
        wake();
    }

    /** One frame of the opening (or of its fading away on return); true while it moves. */
    private boolean tickLaunch(long up) {
        if (launchItem == null) return false;
        if (!launchStarted && up - launchAt >= LAUNCH_MS) {
            launchStarted = true;
            boolean opened = HomeApps.open(activity, launchItem, this, Math.round(launchFrom.left), Math.round(launchFrom.top),
                    Math.round(launchFrom.width()), Math.round(launchFrom.height()), true);
            if (!opened) {
                showToast(Ui.trf("Couldn't open {app}.", "app", launchItem.label));
                launchFadeAt = up;   // nothing opened: the card goes away again
            }
        }
        if (launchFadeAt > 0) {
            float a = 1 - Ui.clamp((up - launchFadeAt) / (float) UNLAUNCH_MS);
            launchLayer.setAlpha(a * a);
            if (a <= 0) {
                launchItem = null;
                launchLayer.setVisibility(INVISIBLE);
                return false;
            }
            return true;
        }
        if (up - launchAt <= LAUNCH_MS + 32) {
            launchLayer.invalidate();
            return true;
        }
        return false;   // full screen, waiting for the app (or for us to come back)
    }

    /**
     * The opening card: from the selected tile to the whole screen (ease-out cubic), its corners straightening, the
     * screen behind darkening; the app's colours and logo (or its banner) in the middle, growing a little, with a
     * glint crossing it.
     */
    private void drawLaunch(Canvas c) {
        HomeApps.Item it = launchItem;
        if (it == null) return;
        float t = Ui.clamp((SystemClock.uptimeMillis() - launchAt) / (float) LAUNCH_MS);
        // Ease in and out (smootherstep): it starts gently and lands gently, no jump in the first frames.
        float e = t * t * t * (t * (t * 6 - 15) + 10);
        launchPaint.setShader(null);
        launchPaint.setColor(Ui.alpha(0xFF000000, 0.6f * e));
        c.drawRect(screen, launchPaint);
        launchRect.set(lerp(launchFrom.left, 0, e), lerp(launchFrom.top, 0, e), lerp(launchFrom.right, w, e), lerp(launchFrom.bottom, h, e));
        float r = lerp(GRID_RADIUS * k, 0, e);
        if (launchFill != null) {
            unitMatrix.setScale(1, launchRect.height());
            unitMatrix.postTranslate(0, launchRect.top);
            launchFill.setLocalMatrix(unitMatrix);
            launchPaint.setShader(launchFill);
            launchPaint.setColor(0xFFFFFFFF);
        } else {
            launchPaint.setColor(0xFF0E0E11);
        }
        c.drawRoundRect(launchRect, r, r, launchPaint);
        launchPaint.setShader(null);
        // The logo on the brand colours, else the app's banner, kept in proportion: from its size on the tile to a
        // little bigger in the middle of the screen.
        // The logo (crisp, at the size the focused tile shows it) on the brand colours, else the app's banner. It grows
        // only a little, so it stays sharp, and fades as the card fills the screen: the last frame is the app's clean
        // colour, which the app then fades in over.
        boolean logo = launchFill != null && it.logo != null;
        Bitmap art = logo ? crispLogo(it, false, false) : it.tile;
        float artAlpha = 1 - Ui.clamp((t - 0.55f) / 0.4f);
        if (art != null && artAlpha > 0.01f) {
            float aw = art.getWidth(), ah = art.getHeight();
            float startFit = logo ? 1 : Math.min(launchFrom.width() / aw, launchFrom.height() / ah);
            float fit = startFit * lerp(1, logo ? 1.35f : 1.6f, e);
            float cx = launchRect.centerX(), cy = launchRect.centerY();
            launchArt.set(cx - aw * fit / 2, cy - ah * fit / 2, cx + aw * fit / 2, cy + ah * fit / 2);
            launchPaint.setColor(0xFFFFFFFF);
            launchPaint.setAlpha(Math.round(255 * artAlpha));
            if (!logo) roundImage(c, art, launchArt, lerp(GRID_RADIUS, 28, e) * k);
            else c.drawBitmap(art, null, launchArt, launchPaint);
            launchPaint.setAlpha(255);
        }
        if (t < 1) {   // a glint across it as it grows
            float band = launchRect.width() * 0.3f, at = launchRect.left - band + (launchRect.width() + band) * e;
            unitMatrix.setScale(band, 1);
            unitMatrix.postSkew(-0.4f, 0, 0, launchRect.centerY());
            unitMatrix.postTranslate(at, 0);
            glint.getShader().setLocalMatrix(unitMatrix);
            glint.setAlpha(Math.round(255 * (1 - t)));
            c.drawRoundRect(launchRect, r, r, glint);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move

    private void hold() {
        if (sheetOpen) toggleShelf(); else startReorder();
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = others.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= HomeApps.MAX_SHELF) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= HomeApps.MAX_SHELF) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Free My TV › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = 0; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(3, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
        if (cinematic()) for (int i = 0; i < rowItems.size(); i++) out.add(a11yId(Z_ROW, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i >= 3) return label(lightButtons, i - 3);
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", 3);
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_ROW: return i < rowItems.size() ? rowItems.get(i).title + ", " + rowMetas[i] : "";
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(3, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_ROW:
                if (i >= rowItems.size()) return false;
                a11yRect.set(rowRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_ROW: return rowIndex < rowItems.size() ? a11yId(Z_ROW, rowIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < 3) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_ROW:
                if (i >= rowItems.size()) return;
                if (drawerOpen) closeDrawer();
                rowIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < hours; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale)).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        // Cinematic: the tabs on the top buttons' line (there is no "For You" title above them).
        float tabsAt = cinematic() ? L_BUTTON_CY - L_TABBAR_H / 2 : L_TABBAR_TOP;
        float pad = 26 * k, tabTop = tabsAt * k, tabBottom = (tabsAt + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        if (cinematic()) {
            // tvOS tab bar at the right, just left of the top buttons (the weather and cricket chips take the left):
            // a capsule 8 px larger than the pills.
            float edge = lightButtons.isEmpty() ? CONTENT_R * k : topRects[3].left - 28 * k;
            float dx = edge - 8 * k - topRects[2].right;
            for (int i = 0; i < 3; i++) topRects[i].offset(dx, 0);
            tabBar.set(topRects[0].left - 8 * k, topRects[0].top - 8 * k, topRects[2].right + 8 * k, topRects[0].bottom + 8 * k);
        }
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, left = MARGIN * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g;
        cardRects[0].set(left, top, left + wide, top + ch);
        cardRects[1].set(cardRects[0].right + g, top, cardRects[0].right + g + tall, top + ch);
        cardRects[2].set(cardRects[1].right + g, top, cardRects[1].right + g + tall, top + ch);
        layoutRow();
        heroDirty = statusDirty = true;
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        List<Recs.Pick> list = recs.now(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three tabs preloaded: switching tabs is instant
        if (recs.hasPhotos()) {
            // Your own photos (Photos): one of them, the next every 10 minutes (this runs every 15 s).
            Backdrop b = recs.photoBackdrop();
            if (b != null) showBackdrop(b);
        } else {
            Backdrop b = cards.isEmpty() ? null : recs.backdrop(cards.get(0));
            if (cards.isEmpty() || b != null) showBackdrop(b);
        }
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : bgEase(up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
        // Your photos: the shading once, as much as the photos show (not once per photo during a cross-fade).
        float photoWeight = (bgTo != null && bgTo.photo ? (fading ? e : 1) : 0) + (fading && bgFrom != null && bgFrom.photo ? 1 - e : 0);
        if (photoWeight > 0.004f) drawPhotoShade(c, Math.min(1, photoWeight));
        if (festiveEffect != Celebrate.NONE) drawFestive(c);
    }

    private boolean festiveMoving() {
        return festiveEffect != Celebrate.NONE && !sheetOpen && sheet.value <= 0.001f && !drawerOpen && drawer.value <= 0.001f;
    }

    private static final int[] CONFETTI_COLORS = {0xFFFF6B6B, 0xFFFFD93D, 0xFF6BCB77, 0xFF4D96FF, 0xFFC77DFF, 0xFFFF9F45};

    /**
     * A light effect over the wallpaper on special days, drawn from the time alone (nothing kept per particle): snow
     * falling, golden sparks rising, confetti or Sankranti's coloured diamonds tumbling down. Few and small: the
     * wallpaper stays the picture.
     */
    private void drawFestive(Canvas c) {
        float t = SystemClock.uptimeMillis() / 1000f;
        int n = festiveEffect == Celebrate.SNOW ? 56 : 40;
        for (int i = 0; i < n; i++) {
            // Each particle's own numbers, the same every frame.
            float a = fract(i * 0.6180339f), b = fract(i * 0.3819660f + 0.17f), d = fract(i * 0.7548776f + 0.41f);
            float size = (festiveEffect == Celebrate.SNOW ? 2.5f + 4 * d : 3 + 5 * d) * k;
            float speed = (festiveEffect == Celebrate.SPARKS ? 22 + 30 * b : 35 + 45 * b) * k;
            float span = h + 40 * k;
            float travel = fract((t * speed + a * span) / span) * span - 20 * k;
            float y = festiveEffect == Celebrate.SPARKS ? h - travel : travel;
            float x = a * w + (float) Math.sin(t * (0.4f + d) + i) * 26 * k;
            switch (festiveEffect) {
                case Celebrate.SNOW:
                    festivePaint.setColor(Ui.alpha(0xFFFFFFFF, 0.55f + 0.3f * d));
                    c.drawCircle(x, y, size, festivePaint);
                    break;
                case Celebrate.SPARKS: {
                    float twinkle = 0.45f + 0.55f * (0.5f + 0.5f * (float) Math.sin(t * (2 + 3 * d) + i * 1.7f));
                    // Brighter low down, fading as they rise.
                    float fade = Math.max(0, Math.min(1, y / (h * 0.9f)));
                    festivePaint.setColor(Ui.alpha(i % 3 == 0 ? 0xFFFFE08A : 0xFFFFB547, twinkle * (0.25f + 0.65f * fade)));
                    c.drawCircle(x, y, size * 0.6f, festivePaint);
                    break;
                }
                default: {   // confetti (rectangles) or Sankranti (diamonds)
                    festivePaint.setColor(Ui.alpha(CONFETTI_COLORS[i % CONFETTI_COLORS.length], 0.8f));
                    c.save();
                    c.translate(x, y);
                    c.rotate(festiveEffect == Celebrate.COLOURS ? 45 : t * (60 + 120 * d) + i * 37);
                    if (festiveEffect == Celebrate.COLOURS) c.drawRect(-size, -size, size, size, festivePaint);
                    else c.drawRect(-size, -size * 0.45f, size, size * 0.45f, festivePaint);
                    c.restore();
                    break;
                }
            }
        }
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    private Paint photoNight, photoDay, photoVeil;
    private int photoShadeW;

    /**
     * What goes over your photo: at night the bottom darkens (behind the shelf and the row); by day (the Light theme)
     * a white veil, stronger behind the clock. Drawn here, over the photo's own pixels, never baked into them.
     */
    private void drawPhotoShade(Canvas c, float weight) {
        if (photoNight == null || photoShadeW != w) {
            photoShadeW = w;
            photoNight = new Paint(Paint.DITHER_FLAG);
            photoNight.setShader(new LinearGradient(0, h * 0.5f, 0, h, new int[] {0x000B0B0D, 0x400B0B0D, 0x8C0B0B0D},
                    new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
            photoDay = new Paint(Paint.DITHER_FLAG);
            photoDay.setColor(0x66FFFFFF);
            photoVeil = new Paint(Paint.DITHER_FLAG);
            photoVeil.setShader(new android.graphics.RadialGradient(w * 0.19f, h * 0.44f, w * 0.34f,
                    new int[] {0x8CFFFFFF, 0x66FFFFFF, 0x26FFFFFF, 0x00FFFFFF}, new float[] {0, 0.45f, 0.75f, 1}, Shader.TileMode.CLAMP));
        }
        if (night > 0.001f) {
            photoNight.setAlpha(Math.round(255 * weight * night));
            c.drawRect(screen, photoNight);
        }
        if (night < 0.999f) {
            float a = weight * (1 - night);
            photoDay.setAlpha(Math.round(255 * a));
            c.drawRect(screen, photoDay);
            photoVeil.setAlpha(Math.round(255 * a));
            c.save();
            c.scale(1, 0.7f, w * 0.19f, h * 0.44f);
            c.drawCircle(w * 0.19f, h * 0.44f, w * 0.34f, photoVeil);
            c.restore();
        }
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (b.photo) {
            if (alpha <= 0.004f) return;
            // Your photo drifts slowly (a Ken Burns): a gentle zoom and pan that never stops and never shows an edge
            // (the zoom stays above 1 + twice the pan).
            float t = SystemClock.uptimeMillis() / 1000f / 75f * (float) (2 * Math.PI) + (System.identityHashCode(b) % 7);
            float zoom = 1.075f + 0.035f * (float) Math.sin(t);   // the edge stays hidden with the parallax too
            c.save();
            c.translate(w * 0.014f * (float) Math.sin(t * 0.7f + 1) + parallax.value * PARALLAX_PX * k,
                    h * 0.012f * (float) Math.cos(t * 0.9f));
            c.scale(zoom, zoom, w / 2f, h / 2f);
        }
        if (b.photo) {
            imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
            c.drawBitmap(b.lightImage, b.srcRect, screen, imagePaint);   // the photo's own pixels
            float blur = Ui.clamp(depthBlur.value);
            if (blur > 0.004f && b.soft != null) {   // depth of field: the soft version over it
                softPaint.setAlpha(Math.round(255 * Math.min(1, alpha) * blur));
                c.drawBitmap(b.soft, null, screen, softPaint);
            }
            c.restore();
            return;
        }
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    /** Where the focus is across the screen (the parallax follows it). */
    private float focusX() {
        if (zone == Z_SHELF && shelfIndex < shelf.size() && shelfIndex < tileRects.length) return tileRects[shelfIndex].centerX();
        if (zone == Z_ROW && rowIndex < rowItems.size() && rowIndex < rowRects.length) return rowRects[rowIndex].centerX();
        if (zone == Z_CRICKET && cricketIndex < CRICKET_MAX) return cricketRects[cricketIndex].centerX() - cricketShift.value;
        return w / 2f;
    }

    /** Your photo is on the background and nothing covers it: it keeps drifting (about 30 frames a second). */
    private boolean photoDrifting() {
        return bgTo != null && bgTo.photo && !sheetOpen && sheet.value <= 0.001f && !drawerOpen && drawer.value <= 0.001f;
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (cinematic()) {
            drawCinematic(c);
            return;
        }
        String hello = Ui.tr("For You");
        c.drawText(hello, MARGIN * k - Ui.leftBearing(lTitle, hello), L_TITLE_BASE * k, lTitle);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        int top = -1;
        for (int i = 0; i < 3; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < 3; i++) if (i != top) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        if (HomeData.coinCount() > 0) drawQuotes(c);
    }

    // MARK: - Cinematic layout (without the picks)

    private boolean cinematic() {
        return !picksOn();
    }

    /** What you played lately (History: Watch Next and our own log), with pictures, read off the main thread. */
    private void loadRow() {
        rowLoadedAt = SystemClock.uptimeMillis();
        final Context app = getContext().getApplicationContext();
        final int artW = Math.round(C_CARD_W * 1.1f * k);   // sharp at the focused size
        final Map<String, Bitmap> have = new HashMap<>(rowArt);
        LOADER.execute(() -> {
            List<History.Entry> list = History.continueWatching(app, ROW_MAX);
            Map<String, Bitmap> art = new HashMap<>();
            for (History.Entry e : list) {
                Bitmap b = have.get(e.key());
                if (b == null) b = History.art(app, e, artW);
                if (b != null) art.put(e.key(), b);
            }
            handler.post(() -> {
                rowItems = list;
                rowArt.clear();
                rowArt.putAll(art);
                layoutRow();
                if (zone == Z_ROW && rowItems.isEmpty()) zone = Z_SHELF;
                rowIndex = Math.min(rowIndex, Math.max(0, rowItems.size() - 1));
                retarget(false);
                heroDirty = true;
            });
        });
    }

    /** Equal cards from the left; their titles and apps made once here (ellipsized to each card). */
    private void layoutRow() {
        if (cRowTitle == null) return;
        float x = MARGIN * k, cw = C_CARD_W * k, ch = cw * 9 / 16, top = C_ROW_TOP * k;
        PackageManager pm = getContext().getPackageManager();
        for (int i = 0; i < rowItems.size(); i++) {
            rowRects[i].set(x, top, x + cw, top + ch);
            x += cw + C_ROW_GAP * k;
            History.Entry e = rowItems.get(i);
            rowTitles[i] = TextUtils.ellipsize(e.title, cRowTitle, cw, TextUtils.TruncateAt.END).toString();
            rowMetas[i] = TextUtils.ellipsize(History.label(pm, e.pkg), cRowMeta, cw, TextUtils.TruncateAt.END).toString();
        }
    }

    private void openEntry(History.Entry e) {
        if (e.intentUri != null) {
            try {
                Intent i = Intent.parseUri(e.intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
                return;
            } catch (Exception ignored) {
                // the deep link is gone: the app itself below
            }
        }
        Intent i = HomeApps.launchIntent(getContext().getPackageManager(), e.pkg);
        if (i == null) {
            showToast(Ui.trf("Couldn't open {app}.", "app", History.label(getContext().getPackageManager(), e.pkg)));
            return;
        }
        try {
            activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ex) {
            AppLog.w("continue watching: " + ex.getMessage());
        }
    }

    /** The bold clock and the date on the left (above Continue watching when there is one), the chips, the tabs, the row. */
    private void drawCinematic(Canvas c) {
        float x = MARGIN * k;
        boolean row = !rowItems.isEmpty();
        // Samsung lock screen: the hours over the minutes, in the wallpaper's color (a little see-through), then the date.
        int accent = accentNow(SystemClock.uptimeMillis());
        int colon = clockText.indexOf(':');
        String hours = colon > 0 ? clockText.substring(0, colon) : clockText, minutes = colon > 0 ? clockText.substring(colon + 1) : "";
        if (hours.length() == 1) hours = "0" + hours;
        float cap = Ui.cap(cClock);
        float line1 = C_GREET_BASE * k + 28 * k + cap, line2 = line1 + cap + 20 * k, dateBase = line2 + 66 * k;
        cGreet.setColor(Ui.alpha(accent, 0.92f));
        c.drawText(greeting, x - Ui.leftBearing(cGreet, greeting), C_GREET_BASE * k, cGreet);
        cClock.setColor(Ui.alpha(accent, 0.92f));
        c.drawText(hours, x - Ui.leftBearing(cClock, hours), line1, cClock);
        c.drawText(minutes, x - Ui.leftBearing(cClock, minutes), line2, cClock);
        if (!amPm.isEmpty()) {   // beside the minutes, top-aligned with them
            cAmPm.setColor(Ui.alpha(accent, 0.9f));
            float ax = x - Ui.leftBearing(cClock, minutes) + cClock.measureText(minutes) + 18 * k;
            c.drawText(amPm, ax, line2 - cap + Ui.cap(cAmPm), cAmPm);
        }
        cDate.setColor(Ui.alpha(accent, 0.92f));
        c.drawText(dateText, x - Ui.leftBearing(cDate, dateText), dateBase, cDate);
        if (!reminderLine.isEmpty()) {
            cGreet.setColor(Ui.alpha(accent, 0.85f));
            float size = cGreet.getTextSize();
            cGreet.setTextSize(28 * k);
            c.drawText(reminderLine, x - Ui.leftBearing(cGreet, reminderLine), dateBase + 46 * k, cGreet);
            cGreet.setTextSize(size);
        }
        drawCinematicTabs(c);
        drawWeatherChip(c);
        cricketVersion = cricket.version;
        if (row) {
            String head = Ui.tr("Continue watching");
            c.drawText(head, x - Ui.leftBearing(cRowHead, head), C_HEAD_BASE * k, cRowHead);
            int top = -1;
            for (int i = 0; i < rowItems.size(); i++) {
                if (rowFocus[i].value > 0.02f && (top < 0 || rowFocus[i].value > rowFocus[top].value)) top = i;
            }
            for (int i = 0; i < rowItems.size(); i++) if (i != top) drawRowCard(c, i);
            if (top >= 0) drawRowCard(c, top);   // the focused one on top of its neighbors as it grows
        }
        drawCricketRow(c);
    }

    // MARK: - Cricket row

    private int cricketCount() {
        return Cricket.hasKey() && cinematic() ? Math.min(CRICKET_MAX, cricket.matches.size()) : 0;
    }

    /** The row's heading baseline: under Continue watching, or in its place when there's none. */
    private float cricketHeadBase() {
        return rowItems.isEmpty() ? C_HEAD_BASE : C_HEAD_BASE + C_CK_BELOW_ROW;
    }

    /** How far the page scrolls up (1920 px scale) so the row and the match's details are on screen. */
    private float cricketScroll() {
        float bottom = cricketHeadBase() + 36 + C_CK_H + 50 + 34 + 46;
        return Math.max(0, bottom - 1080);
    }

    /**
     * The Cricket row: a card per match (both teams with their flags, then the match type and start time, the live
     * score, or the result), live ones marked; the focused card lifts with a white ring, and the match's full name
     * and status show under the row.
     */
    private void drawCricketRow(Canvas c) {
        int n = cricketCount();
        if (n == 0) return;
        java.util.List<Cricket.Match> ms = cricket.matches;
        n = Math.min(n, ms.size());
        float x0 = MARGIN * k, head = cricketHeadBase() * k, top = head + 36 * k;
        String title = Ui.tr("Cricket");
        c.drawText(title, x0 - Ui.leftBearing(cRowHead, title), head, cRowHead);
        float cw = C_CK_W * k, ch = C_CK_H * k, shift = cricketShift.value;
        int focused = -1;
        for (int i = 0; i < n; i++) {
            float x = x0 + i * (C_CK_W + C_CK_GAP) * k - shift;
            cricketRects[i].set(x, top, x + cw, top + ch);
            if (cricketFocus[i].value > 0.02f && (focused < 0 || cricketFocus[i].value > cricketFocus[focused].value)) focused = i;
        }
        for (int i = 0; i < n; i++) if (i != focused) drawCricketCard(c, ms.get(i), i);
        if (focused >= 0) drawCricketCard(c, ms.get(focused), focused);
        // The focused match in full under the row.
        if (zone == Z_CRICKET && cricketIndex < n) {
            float f = Ui.clamp(cricketFocus[cricketIndex].value);
            Cricket.Match m = ms.get(cricketIndex);
            float room = (CONTENT_R - MARGIN) * k, ty = top + ch + 50 * k;
            cCkTitle.setAlpha(Math.round(255 * f));
            c.drawText(TextUtils.ellipsize(m.name, cCkTitle, room, TextUtils.TruncateAt.END).toString(), x0, ty, cCkTitle);
            cCkStatus.setAlpha(Math.round(0x99 * f));
            String st = m.status.isEmpty() ? matchWhen(m) : m.status;
            c.drawText(TextUtils.ellipsize(st, cCkStatus, room, TextUtils.TruncateAt.END).toString(), x0, ty + 34 * k, cCkStatus);
            cCkTitle.setAlpha(255);
        }
    }

    private void drawCricketCard(Canvas c, Cricket.Match m, int i) {
        RectF base = cricketRects[i];
        if (base.right < 0 || base.left > w) return;
        float f = Ui.clamp(cricketFocus[i].value), s = 1 + 0.06f * f, r = 18 * k * s;
        float cx = base.centerX(), cy = base.centerY() - 8 * k * f;
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        tmp.set(cell);
        tmp.offset(0, (8 + 16 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, 0.3f + 0.35f * f));
        shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        fill.setShader(null);
        fill.setColor(blend(0xD91C1C20, 0xF2262529, f));
        c.drawRoundRect(cell, r, r, fill);
        float left = cell.left + 22 * k;
        for (int t = 0; t < 2; t++) {   // the two teams: flag, short name
            float line = cell.top + (40 + t * 36) * k;
            float fw = 34 * k, fh = 24 * k;
            flagRect.set(left, line - fh + 2 * k, left + fw, line + 2 * k);
            Bitmap flag = cricket.flag(m.flagUrls[t]);
            if (flag != null) {
                c.drawBitmap(flag, null, flagRect, bitmapPaint);
            } else {
                fill.setColor(0x33FFFFFF);
                c.drawRoundRect(flagRect, 4 * k, 4 * k, fill);
            }
            c.drawText(m.shorts[t], flagRect.right + 14 * k, line, cCkTeam);
        }
        // The foot: LIVE and the score, the start, or the result.
        float foot = cell.bottom - 20 * k, room = cell.width() - 44 * k;
        if (m.live) {
            String live = Ui.tr("LIVE");
            float lw = cCkLive.measureText(live) + 20 * k;
            fill.setColor(0xFFE5383B);
            c.drawRoundRect(left, foot - 20 * k, left + lw, foot + 6 * k, 6 * k, 6 * k, fill);
            c.drawText(live, left + 10 * k, foot, cCkLive);
            String sc = m.score.isEmpty() ? m.status : m.score;
            c.drawText(TextUtils.ellipsize(sc, cCkMeta, room - lw - 12 * k, TextUtils.TruncateAt.END).toString(),
                    left + lw + 12 * k, foot, cCkMeta);
        } else {
            String meta = m.ended ? m.status : (m.type.isEmpty() ? "" : m.type + "  ·  ") + matchWhen(m);
            c.drawText(TextUtils.ellipsize(meta, cCkMeta, room, TextUtils.TruncateAt.END).toString(), left, foot, cCkMeta);
        }
        if (f > 0.02f) {   // selected: a white ring, as with the apps
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.95f * f));
            rim.setStrokeWidth(3.5f * k);
            float o = 4 * k;
            c.drawRoundRect(cell.left - o, cell.top - o, cell.right + o, cell.bottom + o, r + o, r + o, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    /** "starts 2:00 pm" today, "starts Tue 2:00 pm" on another day. */
    private static String matchWhen(Cricket.Match m) {
        if (m.startsAt <= 0) return "";
        return Ui.trf("starts {time}", "time", matchTime(m.startsAt));
    }

    private static String matchTime(long at) {
        java.util.Calendar a = java.util.Calendar.getInstance(), now = java.util.Calendar.getInstance();
        a.setTimeInMillis(at);
        String time = Celebrate.time(a.get(java.util.Calendar.HOUR_OF_DAY) * 60 + a.get(java.util.Calendar.MINUTE));
        boolean today = a.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR);
        return today ? time : new java.text.SimpleDateFormat("EEE", Ui.locale()).format(a.getTime()) + " " + time;
    }

    /** Under the top buttons: your team's live score (with a red dot) or its next match, while no button has focus. */
    private void drawNextMatch(Canvas c) {
        if (!cinematic() || !Cricket.hasKey() || lightButtons.isEmpty()) return;
        Cricket.Match m = cricket.yourMatch();
        if (m == null) return;
        float focus = 0;
        for (int i = 3; i < 3 + lightButtons.size(); i++) focus = Math.max(focus, Ui.clamp(topFocus[i].value));
        float a = 1 - focus;
        if (a <= 0.01f) return;
        String text = m.live ? (m.score.isEmpty() ? m.shorts[0] + " v " + m.shorts[1] : m.score)
                : m.shorts[0] + " v " + m.shorts[1] + "  ·  " + matchTime(m.startsAt);
        float right = CONTENT_R * k, base = (L_BUTTON_CY + L_BUTTON / 2 + 40) * k;
        cNext.setAlpha(Math.round(0xF2 * a));
        c.drawText(text, right, base, cNext);
        if (m.live) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFF5449, a));
            c.drawCircle(right - cNext.measureText(text) - 16 * k, base - Ui.halfCap(cNext), 7 * k, fill);
        }
        cNext.setAlpha(255);
    }

    /** The two icons drawn here (no font glyph for them): Control Center's sliders and Wi-Fi's waves. */
    private void drawButtonIcon(Canvas c, String symbol, float cx, float cy, float size, int color) {
        if (!symbol.equals("tune") && !symbol.equals("wifi")) {
            Symbols.draw(getContext(), c, symbol, cx, cy, size, color);
            return;
        }
        float u = size * 0.95f;
        iconPaint.setColor(color);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeWidth(u * 0.1f);
        if (symbol.equals("tune")) {
            float[] knob = {-0.22f, 0.24f, -0.02f};
            for (int i = 0; i < 3; i++) {
                float y = cy + (i - 1) * u * 0.34f;
                c.drawLine(cx - u * 0.48f, y, cx + u * 0.48f, y, iconPaint);
                iconPaint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx + knob[i] * u, y, u * 0.12f, iconPaint);
                iconPaint.setStyle(Paint.Style.STROKE);
            }
        } else {
            float by = cy + u * 0.36f;
            for (int i = 1; i <= 3; i++) {
                float rr = u * 0.24f * i;
                tmp.set(cx - rr, by - rr, cx + rr, by + rr);
                c.drawArc(tmp, -135, 90, false, iconPaint);
            }
            iconPaint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, by - u * 0.02f, u * 0.08f, iconPaint);
        }
        iconPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * tvOS tab bar: a smoked capsule at the top center with Home, Apps and Settings. Home (where you are) rests on a
     * faint pill; with focus, a white pill slides from tab to tab and grows a little.
     */
    private void drawCinematicTabs(Canvas c) {
        float rr = tabBar.height() / 2;
        fill.setShader(null);
        fill.setColor(0x66000000);
        c.drawRoundRect(tabBar, rr, rr, fill);
        float shown = Ui.clamp(Math.max(topFocus[0].value, Math.max(topFocus[1].value, topFocus[2].value)));
        if (shown < 0.99f) {   // where you are, while the bar doesn't have the focus
            RectF r = topRects[0];
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.16f * (1 - shown)));
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
        }
        if (shown > 0.01f) {
            float p = Math.max(0, Math.min(2, tabPos.value));
            int a = (int) Math.floor(p), b = Math.min(2, a + 1);
            float t = p - a;
            RectF ra = topRects[a], rb = topRects[b];
            pillRect.set(ra.left + (rb.left - ra.left) * t, ra.top, ra.right + (rb.right - ra.right) * t, ra.bottom);
            float sc = 1 + 0.06f * shown;
            float cx = pillRect.centerX(), cy = pillRect.centerY(), hw = pillRect.width() * sc / 2, hh = pillRect.height() * sc / 2;
            pillRect.set(cx - hw, cy - hh, cx + hw, cy + hh);
            tmp.set(pillRect);
            tmp.offset(0, 8 * k * shown);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * shown));
            shadows.draw(c, tmp, hh, SHADOW_BLUR * k, shadow);
            fill.setColor(Ui.alpha(0xFFFFFFFF, shown));
            c.drawRoundRect(pillRect, hh, hh, fill);
        }
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            float f = Ui.clamp(topFocus[i].value);
            Paint p = i == 0 || f > 0.5f ? lTabOn : lTab;
            p.setColor(blend(i == 0 ? WHITE : 0xD9FFFFFF, L_INK, f));   // dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
    }

    /** OK on a cinematic tab: Home goes back to the apps, Apps opens All Apps, Settings the TV's own settings. */
    private void openTab(int i) {
        if (i == 0) {
            zone = Z_SHELF;
            retarget(false);
            click.move();
            return;
        }
        click.open();
        if (i == 1) {
            openSheet();
            return;
        }
        try {
            activity.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            showToast(Ui.trf("Couldn't open {app}.", "app", Ui.tr("TV Settings")));
        }
    }

    /** "☁ 28° Cloudy" in a smoked pill at the top left; returns where the next chip starts. */
    private float drawWeatherChip(Canvas c) {
        float x0 = MARGIN * k;
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return x0;
        if (data.weatherAt != chipWeatherAt || Prefs.version != chipPrefs || !Ui.language.equals(chipLanguage)) {
            chipWeatherAt = data.weatherAt;
            chipPrefs = Prefs.version;
            chipLanguage = Ui.language;
            chipText = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay);
            chipSymbol = Symbols.weather(wx.code, wx.isDay);
        }
        float size = 30 * k, sw = Symbols.width(getContext(), chipSymbol, size), pad = 22 * k, h = C_CHIP_H * k;
        float w = pad + sw + 12 * k + cChip.measureText(chipText) + pad, cy = L_BUTTON_CY * k;
        chipRect.set(x0, cy - h / 2, x0 + w, cy + h / 2);
        fill.setShader(null);
        fill.setColor(0x73000000);
        c.drawRoundRect(chipRect, h / 2, h / 2, fill);
        Symbols.draw(getContext(), c, chipSymbol, chipRect.left + pad + sw / 2, cy, size, 0xF2FFFFFF);
        c.drawText(chipText, chipRect.left + pad + sw + 12 * k, cy + Ui.halfCap(cChip), cChip);
        return chipRect.right + 16 * k;
    }

    /**
     * A Continue watching card, as on Apple TV: the picture with the progress at its bottom, the title and the app
     * under it. Focus lifts it (bigger, higher, a deeper shadow) and a band of light crosses it as it rises.
     */
    private void drawRowCard(Canvas c, int i) {
        History.Entry e = rowItems.get(i);
        RectF base = rowRects[i];
        float v = rowFocus[i].value, f = Ui.clamp(v), s = 1 + 0.09f * v, r0 = C_RADIUS * k;
        float bw = base.width() * s, bh = base.height() * s, cx = base.centerX(), cy = base.centerY() - 10 * k * v;
        cell.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
        float r = r0 * s;
        tmp.set(cell);
        tmp.offset(0, (10 + 22 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, 0.32f + 0.38f * f));
        shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        boolean deep = f > 0.02f && zone == Z_ROW && i == rowIndex;   // Vision Pro glass: the focused card leans
        float ry = deep ? tilt.value * f : 0;
        if (deep) {
            c.save();
            applyDepth(c, cx, cy, -2f * f, ry);
        }
        Bitmap img = rowArt.get(e.key());
        if (img != null) {
            roundImage(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(0xFF2C2C2E);
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f && f < 0.995f) {
            // The shine crosses the card while the focus spring rises (no timer: it moves with the lift).
            float band = cell.width() * 0.6f, at = cell.left - band + (cell.width() + band) * f;
            shineMatrix.setScale(band, 1);
            shineMatrix.postTranslate(at, 0);
            shine.getShader().setLocalMatrix(shineMatrix);
            c.save();
            c.clipRect(cell);
            c.drawRoundRect(cell, r, r, shine);
            c.restore();
        }
        float p = e.progress();
        if (p > 0.01f) {
            float bx = cell.left + 20 * k, bwid = cell.width() - 40 * k, by = cell.bottom - 16 * k;
            fill.setShader(null);
            fill.setColor(0x66000000);
            c.drawRoundRect(bx - 2 * k, by - 5 * k, bx + bwid + 2 * k, by + 5 * k, 5 * k, 5 * k, fill);
            fill.setColor(0x80FFFFFF);
            c.drawRoundRect(bx, by - 3 * k, bx + bwid, by + 3 * k, 3 * k, 3 * k, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(bx, by - 3 * k, bx + bwid * p, by + 3 * k, 3 * k, 3 * k, fill);
        }
        if (deep) {
            drawGlassLight(c, cell, r, ry, f);
            c.restore();
        }
        // Under the card: the title (it follows the card as it lifts), dimmer until focused. The row sits low on the
        // screen, so there's room for one line (the app shows in the picture).
        float tx = base.left, ty = cell.bottom + 36 * k;
        cRowTitle.setAlpha(Math.round(255 * (0.75f + 0.25f * f)));
        c.drawText(rowTitles[i], tx, ty, cRowTitle);
        cRowTitle.setAlpha(255);
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + HomeData.highLow(wx, "  ");
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (i == 0 ? b.wide : b.tall);
        if (img != null) {
            roundImage(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
                lightShaderW = new float[cardRects.length];
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                lightShaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p == null) return;
        fitPaint.set(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
        if (i == 0 && p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(lCardMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
        }
    }

    /**
     * The round buttons (Live TV, HDMI Inputs and Settings by default): icon-only glass circles (light by day, smoked at
     * night); the focused one turns white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            drawButtonIcon(c, it.symbol, cell.centerX(), cell.centerY(), 27 * k * s, blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            if (f > 0.01f) {
                String label = it.label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
        drawNextMatch(c);
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy > 0 && cinematic() && !rowItems.isEmpty()) {   // the row is under the apps
                    zone = Z_ROW;
                    rowIndex = nearest(rowRects, rowItems.size(), shelf.isEmpty() ? 0 : tileRects[shelfIndex].centerX());
                    break;
                }
                if (dy > 0 && cinematic() && cricketCount() > 0) {   // no Continue watching: the Cricket row is next
                    zone = Z_CRICKET;
                    cricketIndex = Math.min(cricketIndex, cricketCount() - 1);
                    break;
                }
                if (dy >= 0) return false;
                if (cinematic()) {
                    zone = Z_TOP;
                    topIndex = nearest(topRects, 3 + lightButtons.size(), shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                } else if (cards.isEmpty()) {
                    if (!picksOn() && lightButtons.isEmpty()) return true;   // no tabs and no buttons above
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : 0;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cards.size(), shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_ROW:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(rowItems.size() - 1, rowIndex + dx));
                    if (next == rowIndex) return true;
                    rowIndex = next;
                } else if (dy < 0) {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, rowRects[rowIndex].centerX());
                } else if (cricketCount() > 0) {   // the Cricket row: the page scrolls up to it
                    zone = Z_CRICKET;
                    cricketIndex = Math.min(cricketIndex, cricketCount() - 1);
                } else {
                    // Down from the bottom row: the drawer (inputs, shortcuts, weather), as from the apps before.
                    if (drawerItems.isEmpty()) return true;
                    openDrawer();
                }
                break;
            case Z_CRICKET:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cricketCount() - 1, cricketIndex + dx));
                    if (next == cricketIndex) return true;
                    cricketIndex = next;
                } else if (dy < 0) {
                    if (!rowItems.isEmpty()) {
                        zone = Z_ROW;
                        rowIndex = Math.min(rowIndex, rowItems.size() - 1);
                    } else {
                        zone = Z_SHELF;
                    }
                } else {
                    if (drawerItems.isEmpty()) return true;
                    openDrawer();
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(2 + lightButtons.size(), topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    // Like Apple TV tabs: focusing a tab selects it (the cinematic ones open on OK).
                    if (topIndex < 3 && picksOn()) selectTab(topIndex);
                } else if (dy > 0) {
                    if (cinematic()) {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, topRects[topIndex].centerX());
                    } else if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearest(cardRects, cards.size(), topRects[topIndex].centerX());
                    }
                } else if (topIndex < 3 && !lightButtons.isEmpty()) {
                    topIndex = 3;   // up from the tabs reaches the corner buttons without walking the row
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
