package nie.translator.rtranslator.tools;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nie.translator.rtranslator.BuildConfig;
import nie.translator.rtranslator.R;

/**
 * Looks up the latest GitHub release of this fork and offers to open it in the browser.
 * The request sends nothing but the app version in the User-Agent; installing stays a
 * manual step, so the app needs no install permission.
 */
public final class UpdateChecker {
    public static final String KEY_AUTO_CHECK = "autoCheckUpdates";
    private static final String KEY_LAST_CHECK = "lastUpdateCheck";
    private static final String KEY_SKIPPED_VERSION = "skippedUpdateVersion";
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/maoruiQa/TransPocket/releases/latest";
    private static final String RELEASES_PAGE = "https://github.com/maoruiQa/TransPocket/releases/latest";
    private static final long CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000;
    private static final int TIMEOUT_MILLIS = 10000;
    private static final int MAX_NOTES_LENGTH = 1200;
    private static final Pattern VERSION = Pattern.compile("\\d+(?:\\.\\d+)+");

    private UpdateChecker() {
    }

    public static final class Release {
        public final String version;
        public final String pageUrl;
        @Nullable
        public final String apkUrl;
        public final String notes;

        Release(String version, String pageUrl, @Nullable String apkUrl, String notes) {
            this.version = version;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
            this.notes = notes;
        }
    }

    /** Launch check: at most once a day, silent unless a newer release that was not skipped exists. */
    public static void checkIfDue(final Activity activity) {
        final SharedPreferences preferences = activity.getSharedPreferences("default", Context.MODE_PRIVATE);
        if (!preferences.getBoolean(KEY_AUTO_CHECK, true)) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = preferences.getLong(KEY_LAST_CHECK, 0);
        if (now >= last && now - last < CHECK_INTERVAL_MILLIS) {
            return;
        }
        preferences.edit().putLong(KEY_LAST_CHECK, now).apply();
        fetch(activity, new Callback() {
            @Override
            void onRelease(@Nullable Release release) {
                if (release != null && isNewer(release.version, BuildConfig.VERSION_NAME)
                        && !release.version.equals(preferences.getString(KEY_SKIPPED_VERSION, null))) {
                    showDialog(activity, release);
                }
            }
        });
    }

    /** Settings button: always reports the outcome. */
    public static void checkNow(final Activity activity) {
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
        fetch(activity, new Callback() {
            @Override
            void onRelease(@Nullable Release release) {
                if (release == null) {
                    Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
                } else if (isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    showDialog(activity, release);
                } else {
                    Toast.makeText(activity, activity.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private abstract static class Callback {
        abstract void onRelease(@Nullable Release release);
    }

    private static void fetch(final Activity activity, final Callback callback) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Release release = null;
            try {
                release = parse(download());
            } catch (Exception e) {
                Log.w("update", "release check failed: " + e.getMessage());
            }
            final Release result = release;
            mainHandler.post(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    callback.onRelease(result);
                }
            });
        }, "update-check").start();
    }

    private static String download() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "TransPocket/" + BuildConfig.VERSION_NAME);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code);
            }
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    body.write(buffer, 0, read);
                }
                return new String(body.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** The tag of a release can be a placeholder, so the name and the APK name are tried too. */
    @Nullable
    private static Release parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String apkUrl = null;
        String apkName = "";
        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");
                if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    apkName = name;
                    apkUrl = asset.optString("browser_download_url", null);
                    break;
                }
            }
        }
        String version = extractVersion(root.optString("tag_name", ""));
        if (version == null) {
            version = extractVersion(root.optString("name", ""));
        }
        if (version == null) {
            version = extractVersion(apkName);
        }
        if (version == null) {
            return null;
        }
        String pageUrl = root.optString("html_url", RELEASES_PAGE);
        if (!pageUrl.startsWith("https://github.com/")) {
            pageUrl = RELEASES_PAGE;
        }
        if (apkUrl != null && !apkUrl.startsWith("https://github.com/")) {
            apkUrl = null;
        }
        String notes = root.optString("body", "").trim();
        if (notes.length() > MAX_NOTES_LENGTH) {
            notes = notes.substring(0, MAX_NOTES_LENGTH) + "…";
        }
        return new Release(version, pageUrl, apkUrl, notes);
    }

    @Nullable
    static String extractVersion(@Nullable String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = VERSION.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    /** Numeric comparison per dot-separated part; a missing part counts as 0. */
    static boolean isNewer(@Nullable String candidate, @Nullable String current) {
        String left = extractVersion(candidate);
        String right = extractVersion(current);
        if (left == null) {
            return false;
        }
        if (right == null) {
            return true;
        }
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? parsePart(a[i]) : 0;
            long y = i < b.length ? parsePart(b[i]) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static long parsePart(String part) {
        try {
            return Long.parseLong(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void showDialog(final Activity activity, final Release release) {
        String message = release.notes.isEmpty()
                ? activity.getString(R.string.update_available_message, BuildConfig.VERSION_NAME, release.version)
                : activity.getString(R.string.update_available_message, BuildConfig.VERSION_NAME, release.version) + "\n\n" + release.notes;
        new MaterialAlertDialogBuilder(activity, R.style.MyThemeOverlay_MaterialComponents_MaterialAlertDialogWithTitle)
                .setTitle(activity.getString(R.string.update_available_title, release.version))
                .setMessage(message)
                .setPositiveButton(R.string.update_download, (dialog, which) -> openInBrowser(activity, release.apkUrl != null ? release.apkUrl : release.pageUrl))
                .setNeutralButton(R.string.update_skip_version, (dialog, which) -> activity.getSharedPreferences("default", Context.MODE_PRIVATE)
                        .edit().putString(KEY_SKIPPED_VERSION, release.version).apply())
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private static void openInBrowser(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
