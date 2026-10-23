package nie.translator.rtranslator.settings;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.MicSensitivity;

/**
 * Extended sensitivity is stored in the named "default" preferences.
 * The stock preference file is not used.
 */
public class MicSensitivityExtendedPreference extends SwitchPreference {
    private Global global;

    public MicSensitivityExtendedPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public MicSensitivityExtendedPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public MicSensitivityExtendedPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MicSensitivityExtendedPreference(Context context) {
        super(context);
    }

    public void setFragment(@NonNull final SettingsFragment fragment) {
        global = (Global) fragment.requireActivity().getApplication();
        setSummary(getContext().getString(R.string.preference_description_mic_sensitivity_extended, MicSensitivity.EXTENDED_MAX));
        setChecked(global.isMicSensitivityExtended());
        setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                boolean extended = (Boolean) newValue;
                global.setMicSensitivityExtended(extended);
                SeekBarPreference slider = (SeekBarPreference) fragment.findPreference("micSensibilitySetting");
                if (slider != null) {
                    slider.applyMicSensitivityRange(extended);
                }
                return true;
            }
        });
    }
}
