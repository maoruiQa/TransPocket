package nie.translator.rtranslator.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageManager;

import org.junit.Test;

import nie.translator.rtranslator.R;

public class ThemeManagerTest {

    private static final int DEFAULT = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    private static final int ENABLED = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    private static final int DISABLED = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

    @Test
    public void normalizePaletteKeepsKnownKeys() {
        assertEquals(ThemeManager.PALETTE_GOLDEN, ThemeManager.normalizePalette(ThemeManager.PALETTE_GOLDEN));
        assertEquals(ThemeManager.PALETTE_BAMBOO, ThemeManager.normalizePalette(ThemeManager.PALETTE_BAMBOO));
        assertEquals(ThemeManager.PALETTE_INDIGO, ThemeManager.normalizePalette(ThemeManager.PALETTE_INDIGO));
        assertEquals(ThemeManager.PALETTE_SAKURA, ThemeManager.normalizePalette(ThemeManager.PALETTE_SAKURA));
        assertEquals(ThemeManager.PALETTE_WISTERIA, ThemeManager.normalizePalette(ThemeManager.PALETTE_WISTERIA));
    }

    @Test
    public void normalizePaletteFallsBackToGolden() {
        assertEquals(ThemeManager.DEFAULT_PALETTE, ThemeManager.normalizePalette(null));
        assertEquals(ThemeManager.DEFAULT_PALETTE, ThemeManager.normalizePalette(""));
        assertEquals(ThemeManager.DEFAULT_PALETTE, ThemeManager.normalizePalette("unknown"));
    }

    @Test
    public void legacyYellowKeysMapToGolden() {
        assertEquals(ThemeManager.PALETTE_GOLDEN, ThemeManager.normalizePalette("ginkgo"));
        assertEquals(ThemeManager.PALETTE_GOLDEN, ThemeManager.normalizePalette("autumn_gold"));
    }

    @Test
    public void recentsIconFollowsPaletteInsteadOfTheApplicationIcon() {
        assertEquals(R.mipmap.ic_launcher_golden, ThemeManager.getLauncherIconResource(ThemeManager.PALETTE_GOLDEN));
        assertEquals(R.mipmap.ic_launcher_bamboo, ThemeManager.getLauncherIconResource(ThemeManager.PALETTE_BAMBOO));
        assertEquals(R.mipmap.ic_launcher_indigo, ThemeManager.getLauncherIconResource(ThemeManager.PALETTE_INDIGO));
        assertEquals(R.mipmap.ic_launcher_sakura, ThemeManager.getLauncherIconResource(ThemeManager.PALETTE_SAKURA));
        assertEquals(R.mipmap.ic_launcher_wisteria, ThemeManager.getLauncherIconResource(ThemeManager.PALETTE_WISTERIA));
        assertEquals(R.mipmap.ic_launcher_golden, ThemeManager.getLauncherIconResource("autumn_gold"));
        assertFalse(ThemeManager.getLauncherIconResource(ThemeManager.PALETTE_BAMBOO) == R.mipmap.ic_launcher);
    }

    @Test
    public void launcherClassNameMatchesManifestComponents() {
        assertEquals("nie.translator.rtranslator.LauncherGolden",
                ThemeManager.launcherClassName(ThemeManager.PALETTE_GOLDEN));
        assertEquals("nie.translator.rtranslator.LauncherBamboo",
                ThemeManager.launcherClassName(ThemeManager.PALETTE_BAMBOO));
        assertEquals("nie.translator.rtranslator.LauncherIndigo",
                ThemeManager.launcherClassName(ThemeManager.PALETTE_INDIGO));
        assertEquals("nie.translator.rtranslator.LauncherSakura",
                ThemeManager.launcherClassName(ThemeManager.PALETTE_SAKURA));
        assertEquals("nie.translator.rtranslator.LauncherWisteria",
                ThemeManager.launcherClassName(ThemeManager.PALETTE_WISTERIA));
        assertEquals("nie.translator.rtranslator.LauncherGolden",
                ThemeManager.launcherClassName("autumn_gold"));
        assertEquals("nie.translator.rtranslator.LauncherGoldenAlt",
                ThemeManager.launcherClassName(ThemeManager.PALETTE_GOLDEN, true));
        assertEquals("nie.translator.rtranslator.LauncherBamboo",
                ThemeManager.launcherClassName(ThemeManager.PALETTE_BAMBOO, true));
    }

    @Test
    public void launcherComponentClassStaysInTheNamespace() {
        assertEquals("nie.translator.rtranslator.LauncherBamboo",
                ThemeManager.launcherComponentClass("LauncherBamboo"));
        assertEquals("nie.translator.rtranslator.LauncherGolden",
                ThemeManager.launcherComponentClass(ThemeManager.LAUNCHER_GOLDEN));
        assertFalse("io.github.maoruiqa.transpocket.LauncherBamboo"
                .equals(ThemeManager.launcherComponentClass("LauncherBamboo")));
    }

    @Test
    public void matchingEffectiveStateWritesNothing() {
        assertEquals(ThemeManager.NO_COMPONENT_WRITE, ThemeManager.settingToWrite(DEFAULT, true, true));
        assertEquals(ThemeManager.NO_COMPONENT_WRITE, ThemeManager.settingToWrite(DEFAULT, false, false));
        assertEquals(ThemeManager.NO_COMPONENT_WRITE, ThemeManager.settingToWrite(DISABLED, false, false));
        assertEquals(ThemeManager.NO_COMPONENT_WRITE, ThemeManager.settingToWrite(ENABLED, true, false));
    }

    @Test
    public void turnOffUsesDefaultUnlessManifestEnablesTheComponent() {
        assertEquals(DEFAULT, ThemeManager.settingToWrite(ENABLED, false, false));
        assertEquals(DISABLED, ThemeManager.settingToWrite(ENABLED, false, true));
        assertEquals(ENABLED, ThemeManager.settingToWrite(DEFAULT, true, false));
    }

    @Test
    public void explicitDisabledGoldenCountsAsRetired() {
        assertTrue(ThemeManager.goldenEntryRetired(false, DISABLED));
        assertFalse(ThemeManager.goldenEntryRetired(false, DEFAULT));
        assertFalse(ThemeManager.goldenEntryRetired(false, ENABLED));
        assertTrue(ThemeManager.goldenEntryRetired(true, ENABLED));
    }

    @Test
    public void leavingGoldenEnablesTheNextIconBeforeDisablingTheInstallEntry() {
        ThemeManager.LauncherIconPlan plan = ThemeManager.planLauncherIcon(
                ThemeManager.PALETTE_BAMBOO, false, freshSettings(), false);
        assertFalse(plan.deferred);
        assertTrue(plan.retiresGoldenEntry);
        assertEquals(2, plan.classNames.length);
        assertEquals("LauncherBamboo", plan.classNames[0]);
        assertEquals(ENABLED, plan.states[0]);
        assertEquals(ThemeManager.LAUNCHER_GOLDEN, plan.classNames[1]);
        assertEquals(DISABLED, plan.states[1]);
    }

    @Test
    public void freshGoldenLaunchWritesNothing() {
        ThemeManager.LauncherIconPlan plan = ThemeManager.planLauncherIcon(
                ThemeManager.PALETTE_GOLDEN, false, freshSettings(), false);
        assertFalse(plan.deferred);
        assertEquals(0, plan.classNames.length);
    }

    @Test
    public void retiredGoldenSwitchesWithoutAnExplicitDisable() {
        int[] current = freshSettings();
        current[index(ThemeManager.LAUNCHER_GOLDEN)] = DISABLED;
        current[index("LauncherSakura")] = ENABLED;
        ThemeManager.LauncherIconPlan plan = ThemeManager.planLauncherIcon(
                ThemeManager.PALETTE_GOLDEN, true, current, false);
        assertFalse(plan.retiresGoldenEntry);
        assertEquals(2, plan.classNames.length);
        assertEquals(ThemeManager.LAUNCHER_GOLDEN_ALT, plan.classNames[0]);
        assertEquals(ENABLED, plan.states[0]);
        assertEquals("LauncherSakura", plan.classNames[1]);
        assertEquals(DEFAULT, plan.states[1]);
    }

    @Test
    public void translationServiceDefersTheWholeGoldenRetirement() {
        ThemeManager.LauncherIconPlan plan = ThemeManager.planLauncherIcon(
                ThemeManager.PALETTE_INDIGO, false, freshSettings(), true);
        assertTrue(plan.deferred);
        assertTrue(plan.retiresGoldenEntry);
        assertEquals(0, plan.classNames.length);
    }

    @Test
    public void translationServiceStillSwitchesWhenGoldenIsAlreadyRetired() {
        int[] current = freshSettings();
        current[index(ThemeManager.LAUNCHER_GOLDEN)] = DISABLED;
        current[index("LauncherBamboo")] = ENABLED;
        ThemeManager.LauncherIconPlan plan = ThemeManager.planLauncherIcon(
                ThemeManager.PALETTE_SAKURA, true, current, true);
        assertFalse(plan.deferred);
        assertFalse(plan.retiresGoldenEntry);
        assertEquals("LauncherSakura", plan.classNames[0]);
        assertEquals(ENABLED, plan.states[0]);
        assertEquals("LauncherBamboo", plan.classNames[1]);
        assertEquals(DEFAULT, plan.states[1]);
    }

    private static int[] freshSettings() {
        int[] settings = new int[ThemeManager.LAUNCHER_CLASSES.length];
        for (int i = 0; i < settings.length; i++) {
            settings[i] = DEFAULT;
        }
        return settings;
    }

    private static int index(String simpleName) {
        for (int i = 0; i < ThemeManager.LAUNCHER_CLASSES.length; i++) {
            if (ThemeManager.LAUNCHER_CLASSES[i].equals(simpleName)) {
                return i;
            }
        }
        throw new AssertionError(simpleName);
    }
}