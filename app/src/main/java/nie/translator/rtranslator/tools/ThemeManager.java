/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.tools;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.WeakHashMap;

import nie.translator.rtranslator.R;
import nie.translator.rtranslator.settings.SettingsActivity;
import nie.translator.rtranslator.voice_translation._conversation_mode._conversation.ConversationService;
import nie.translator.rtranslator.voice_translation._live_translation._simultaneous.SimultaneousService;
import nie.translator.rtranslator.voice_translation._walkie_talkie_mode._walkie_talkie.WalkieTalkieService;

public final class ThemeManager {
    public static final String PREF_COLOR_PALETTE = "colorPalette";
    public static final String PALETTE_GOLDEN = "golden";
    public static final String PALETTE_BAMBOO = "bamboo";
    public static final String PALETTE_INDIGO = "indigo";
    public static final String PALETTE_SAKURA = "sakura";
    public static final String PALETTE_WISTERIA = "wisteria";
    public static final String DEFAULT_PALETTE = PALETTE_GOLDEN;

    // Earlier builds used other names for the yellow default; both keep the default palette.
    private static final String LEGACY_PALETTE_GINKGO = "ginkgo";
    private static final String LEGACY_PALETTE_AUTUMN_GOLD = "autumn_gold";

    private static final String[] PALETTES = new String[]{
            PALETTE_GOLDEN, PALETTE_BAMBOO, PALETTE_INDIGO, PALETTE_SAKURA, PALETTE_WISTERIA
    };

    static final String PREF_LAUNCHER_GOLDEN_RETIRED = "launcherGoldenRetired";
    static final String PREF_LAUNCHER_ICON_DEFERRED = "launcherIconDeferred";

    /** Not a PackageManager state. Means the component already has the wanted effective state. */
    static final int NO_COMPONENT_WRITE = -1;

    static final String LAUNCHER_GOLDEN = "LauncherGolden";
    static final String LAUNCHER_GOLDEN_ALT = "LauncherGoldenAlt";

    // Index 0 is the install-time entry. Its manifest enabled value is true; every other entry is false.
    static final String[] LAUNCHER_CLASSES = new String[]{
            LAUNCHER_GOLDEN,
            LAUNCHER_GOLDEN_ALT,
            "LauncherBamboo",
            "LauncherIndigo",
            "LauncherSakura",
            "LauncherWisteria"
    };

    // Activities record the palette they were created with, so a palette change made in
    // Settings can recreate the screens below it when they resume.
    private static final WeakHashMap<Activity, String> appliedPalettes = new WeakHashMap<>();

    private ThemeManager() {
    }

    public static String getPalette(Context context) {
        String palette = context.getSharedPreferences("default", Context.MODE_PRIVATE)
                .getString(PREF_COLOR_PALETTE, DEFAULT_PALETTE);
        return normalizePalette(palette);
    }

    public static String normalizePalette(String palette) {
        if (LEGACY_PALETTE_GINKGO.equals(palette) || LEGACY_PALETTE_AUTUMN_GOLD.equals(palette)) {
            return PALETTE_GOLDEN;
        }
        for (String candidate : PALETTES) {
            if (candidate.equals(palette)) {
                return candidate;
            }
        }
        return DEFAULT_PALETTE;
    }

    public static void setPalette(Context context, String palette) {
        context.getSharedPreferences("default", Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_COLOR_PALETTE, normalizePalette(palette))
                .apply();
        applyLauncherIcon(context);
        if (context instanceof Activity) {
            applyTaskIcon((Activity) context);
        }
    }

    // Each palette has its own launcher activity, because an activity-alias copies the target
    // activity theme and would always cold-start with the golden splash. The enabled activity
    // is the home-screen icon. Turning a manifest-disabled activity off writes DEFAULT, which
    // keeps the process. The install-time golden activity is the one component whose manifest
    // default is enabled, so turning that one off is explicit and can still stop the process.
    public static void applyLauncherIcon(Context context) {
        PackageManager packageManager = context.getPackageManager();
        String packageName = context.getPackageName();
        int[] current = new int[LAUNCHER_CLASSES.length];
        try {
            for (int i = 0; i < LAUNCHER_CLASSES.length; i++) {
                current[i] = packageManager.getComponentEnabledSetting(
                        new ComponentName(packageName, packageName + "." + LAUNCHER_CLASSES[i]));
            }
        } catch (IllegalArgumentException ignored) {
            // Builds without these launcher activities must keep working; the theme is independent.
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        boolean savedRetired = prefs.getBoolean(PREF_LAUNCHER_GOLDEN_RETIRED, false);
        boolean retired = goldenEntryRetired(savedRetired, current[0]);
        if (retired && !savedRetired) {
            prefs.edit().putBoolean(PREF_LAUNCHER_GOLDEN_RETIRED, true).commit();
            savedRetired = true;
        }

        LauncherIconPlan plan = planLauncherIcon(getPalette(context), retired, current, translationServiceRunning());
        if (plan.deferred) {
            prefs.edit().putBoolean(PREF_LAUNCHER_ICON_DEFERRED, true).commit();
            return;
        }
        // Persist before the explicit disable. A process stop during that call must not
        // turn the install-time entry back on at the next launch.
        if (plan.retiresGoldenEntry && !savedRetired) {
            prefs.edit().putBoolean(PREF_LAUNCHER_GOLDEN_RETIRED, true).commit();
        }
        if (plan.classNames.length == 0) {
            clearDeferredLauncherIcon(prefs);
            return;
        }
        try {
            applyComponentSettings(packageManager, packageName, plan);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        clearDeferredLauncherIcon(prefs);
    }

    /** Class name of the launcher activity for this palette. Must match the manifest component. */
    public static String launcherClassName(String packageName, String palette) {
        return launcherClassName(packageName, palette, false);
    }

    public static String launcherClassName(String packageName, String palette, boolean goldenRetired) {
        return packageName + "." + launcherSimpleName(palette, goldenRetired);
    }

    static String launcherSimpleName(String palette, boolean goldenRetired) {
        String normalized = normalizePalette(palette);
        if (PALETTE_GOLDEN.equals(normalized) && goldenRetired) {
            return LAUNCHER_GOLDEN_ALT;
        }
        return "Launcher" + Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    static boolean launcherManifestEnabled(String simpleName) {
        return LAUNCHER_GOLDEN.equals(simpleName);
    }

    static boolean goldenEntryRetired(boolean savedRetired, int goldenCurrentSetting) {
        return savedRetired
                || goldenCurrentSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    static boolean effectivelyEnabled(int currentSetting, boolean manifestEnabled) {
        return currentSetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || (currentSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && manifestEnabled);
    }

    static int settingToWrite(int currentSetting, boolean shouldEnable, boolean manifestEnabled) {
        if (effectivelyEnabled(currentSetting, manifestEnabled) == shouldEnable) {
            return NO_COMPONENT_WRITE;
        }
        if (shouldEnable) {
            return PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        }
        if (manifestEnabled) {
            return PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        }
        return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    }

    static LauncherIconPlan planLauncherIcon(String palette, boolean goldenRetired, int[] currentSettings,
            boolean translationServiceRunning) {
        String selected = launcherSimpleName(palette, goldenRetired);
        ArrayList<String> names = new ArrayList<>();
        ArrayList<Integer> states = new ArrayList<>();
        boolean retiresGoldenEntry = false;
        for (int i = 0; i < LAUNCHER_CLASSES.length; i++) {
            String simpleName = LAUNCHER_CLASSES[i];
            boolean manifestEnabled = launcherManifestEnabled(simpleName);
            int write = settingToWrite(currentSettings[i], simpleName.equals(selected), manifestEnabled);
            if (write == NO_COMPONENT_WRITE) {
                continue;
            }
            if (LAUNCHER_GOLDEN.equals(simpleName)
                    && write == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                retiresGoldenEntry = true;
            }
            names.add(simpleName);
            states.add(write);
        }
        if (retiresGoldenEntry && translationServiceRunning) {
            return new LauncherIconPlan(true, true, new String[0], new int[0]);
        }
        int count = names.size();
        String[] orderedNames = new String[count];
        int[] orderedStates = new int[count];
        int cursor = 0;
        for (int pass = 0; pass < 2; pass++) {
            boolean wantEnabled = pass == 0;
            for (int i = 0; i < count; i++) {
                boolean isEnabled = states.get(i) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
                if (isEnabled == wantEnabled) {
                    orderedNames[cursor] = names.get(i);
                    orderedStates[cursor] = states.get(i);
                    cursor++;
                }
            }
        }
        return new LauncherIconPlan(false, retiresGoldenEntry, orderedNames, orderedStates);
    }

    private static boolean translationServiceRunning() {
        return ConversationService.isRunning
                || WalkieTalkieService.isRunning
                || SimultaneousService.isRunning;
    }

    private static void clearDeferredLauncherIcon(SharedPreferences prefs) {
        if (prefs.getBoolean(PREF_LAUNCHER_ICON_DEFERRED, false)) {
            prefs.edit().putBoolean(PREF_LAUNCHER_ICON_DEFERRED, false).apply();
        }
    }

    private static void applyComponentSettings(PackageManager packageManager, String packageName,
            LauncherIconPlan plan) {
        if (Build.VERSION.SDK_INT >= 33) {
            Api33ComponentSettings.apply(packageManager, packageName, plan);
            return;
        }
        for (int i = 0; i < plan.classNames.length; i++) {
            packageManager.setComponentEnabledSetting(
                    new ComponentName(packageName, packageName + "." + plan.classNames[i]),
                    plan.states[i],
                    PackageManager.DONT_KILL_APP);
        }
    }

    /** Loaded only on API 33+, where {@link PackageManager.ComponentEnabledSetting} exists. */
    private static final class Api33ComponentSettings {
        @RequiresApi(33)
        static void apply(PackageManager packageManager, String packageName, LauncherIconPlan plan) {
            ArrayList<PackageManager.ComponentEnabledSetting> settings = new ArrayList<>();
            for (int i = 0; i < plan.classNames.length; i++) {
                settings.add(new PackageManager.ComponentEnabledSetting(
                        new ComponentName(packageName, packageName + "." + plan.classNames[i]),
                        plan.states[i],
                        PackageManager.DONT_KILL_APP));
            }
            packageManager.setComponentEnabledSettings(settings);
        }
    }

    static final class LauncherIconPlan {
        final boolean deferred;
        final boolean retiresGoldenEntry;
        final String[] classNames;
        final int[] states;

        LauncherIconPlan(boolean deferred, boolean retiresGoldenEntry, String[] classNames, int[] states) {
            this.deferred = deferred;
            this.retiresGoldenEntry = retiresGoldenEntry;
            this.classNames = classNames;
            this.states = states;
        }
    }

    public static void applyTheme(Activity activity) {
        String palette = getPalette(activity);
        appliedPalettes.put(activity, palette);
        activity.setTheme(getThemeResource(activity, palette));
    }

    public static void recreateIfPaletteChanged(Activity activity) {
        String applied = appliedPalettes.get(activity);
        if (applied != null && !applied.equals(getPalette(activity))) {
            activity.recreate();
        }
    }

    public static int getColor(Context context, @AttrRes int attr) {
        return MaterialColors.getColor(context, attr, android.graphics.Color.BLACK);
    }

    public static ColorStateList getColorStateList(Context context, @AttrRes int attr) {
        return ColorStateList.valueOf(getColor(context, attr));
    }

    @StringRes
    public static int getPaletteNameResource(String palette) {
        switch (normalizePalette(palette)) {
            case PALETTE_BAMBOO:
                return R.string.palette_bamboo;
            case PALETTE_INDIGO:
                return R.string.palette_indigo;
            case PALETTE_SAKURA:
                return R.string.palette_sakura;
            case PALETTE_WISTERIA:
                return R.string.palette_wisteria;
            case PALETTE_GOLDEN:
            default:
                return R.string.palette_golden;
        }
    }

    @ColorRes
    public static int getPalettePrimaryColorResource(String palette) {
        switch (normalizePalette(palette)) {
            case PALETTE_BAMBOO:
                return R.color.bamboo_primary;
            case PALETTE_INDIGO:
                return R.color.indigo_primary;
            case PALETTE_SAKURA:
                return R.color.sakura_primary;
            case PALETTE_WISTERIA:
                return R.color.wisteria_primary;
            case PALETTE_GOLDEN:
            default:
                return R.color.golden_primary;
        }
    }

    // The overview screen draws TaskDescription. Activities in the task inherit the application
    // icon, which is the original yellow mipmap, so the palette icon has to be set here.
    private static final HashMap<String, Bitmap> taskIcons = new HashMap<>();

    @DrawableRes
    public static int getLauncherIconResource(String palette) {
        switch (normalizePalette(palette)) {
            case PALETTE_BAMBOO:
                return R.mipmap.ic_launcher_bamboo;
            case PALETTE_INDIGO:
                return R.mipmap.ic_launcher_indigo;
            case PALETTE_SAKURA:
                return R.mipmap.ic_launcher_sakura;
            case PALETTE_WISTERIA:
                return R.mipmap.ic_launcher_wisteria;
            case PALETTE_GOLDEN:
            default:
                return R.mipmap.ic_launcher_golden;
        }
    }

    @SuppressWarnings("deprecation")
    public static void applyTaskIcon(Activity activity) {
        String palette = getPalette(activity);
        int iconRes = getLauncherIconResource(palette);
        int color = activity.getColor(getPalettePrimaryColorResource(palette));
        Bitmap icon = taskIconBitmap(activity, palette, iconRes);
        ActivityManager.TaskDescription description = icon != null
                ? new ActivityManager.TaskDescription(activity.getString(R.string.app_name), icon, color)
                : new ActivityManager.TaskDescription(activity.getString(R.string.app_name), iconRes, color);
        activity.setTaskDescription(description);
    }

    private static Bitmap taskIconBitmap(Context context, String palette, @DrawableRes int iconRes) {
        // The same mipmap id resolves to the dark mark or the light mark from uiMode.
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        String key = normalizePalette(palette) + "@" + context.getResources().getDisplayMetrics().density + "@" + night;
        Bitmap cached = taskIcons.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        Drawable drawable = ResourcesCompat.getDrawable(context.getResources(), iconRes, context.getTheme());
        if (drawable == null) {
            return null;
        }
        int size = Math.round(72 * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        taskIcons.put(key, bitmap);
        return bitmap;
    }

    private static int getThemeResource(Activity activity, String palette) {
        boolean settings = activity instanceof SettingsActivity;
        switch (palette) {
            case PALETTE_BAMBOO:
                return settings ? R.style.Theme_Settings_Bamboo : R.style.Theme_Speech_Bamboo;
            case PALETTE_INDIGO:
                return settings ? R.style.Theme_Settings_Indigo : R.style.Theme_Speech_Indigo;
            case PALETTE_SAKURA:
                return settings ? R.style.Theme_Settings_Sakura : R.style.Theme_Speech_Sakura;
            case PALETTE_WISTERIA:
                return settings ? R.style.Theme_Settings_Wisteria : R.style.Theme_Speech_Wisteria;
            case PALETTE_GOLDEN:
            default:
                return settings ? R.style.Theme_Settings : R.style.Theme_Speech;
        }
    }
}