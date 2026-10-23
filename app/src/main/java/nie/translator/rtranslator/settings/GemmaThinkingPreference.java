package nie.translator.rtranslator.settings;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

import nie.translator.rtranslator.Global;

/**
 * TranslateGemma thinking is stored in the named "default" preferences.
 * The stock preference file is not used.
 */
public class GemmaThinkingPreference extends SwitchPreference {
    private Global global;

    public GemmaThinkingPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public GemmaThinkingPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public GemmaThinkingPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GemmaThinkingPreference(Context context) {
        super(context);
    }

    public void setFragment(@NonNull SettingsFragment fragment) {
        global = (Global) fragment.requireActivity().getApplication();
        setChecked(global.isGemmaThinkingEnabled());
        setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                global.setGemmaThinkingEnabled((Boolean) newValue);
                return true;
            }
        });
    }
}
