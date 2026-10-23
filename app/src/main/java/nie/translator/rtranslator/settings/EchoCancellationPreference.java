package nie.translator.rtranslator.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

import nie.translator.rtranslator.Global;

public class EchoCancellationPreference extends SwitchPreference {
    private Global global;

    public EchoCancellationPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public EchoCancellationPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public EchoCancellationPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EchoCancellationPreference(Context context) {
        super(context);
    }

    public void setFragment(@NonNull SettingsFragment fragment) {
        global = (Global) fragment.requireActivity().getApplication();
        setChecked(global.getSharedPreferences("default", Context.MODE_PRIVATE).getBoolean("echoCancellation", true));
        setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                SharedPreferences.Editor editor = global.getSharedPreferences("default", Context.MODE_PRIVATE).edit();
                editor.putBoolean("echoCancellation", (Boolean) newValue);
                editor.apply();
                return true;
            }
        });
    }
}
