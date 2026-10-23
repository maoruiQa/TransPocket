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

package nie.translator.rtranslator.settings;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.voice_translation._live_translation._simultaneous.SimultaneousAutoSpeak;

public class SimultaneousAutoSpeakPreference extends Preference {
    private static final String[] VALUES = new String[] {
            SimultaneousAutoSpeak.ON,
            SimultaneousAutoSpeak.OFF,
            SimultaneousAutoSpeak.AUTO
    };

    private Global global;

    public SimultaneousAutoSpeakPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public SimultaneousAutoSpeakPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SimultaneousAutoSpeakPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SimultaneousAutoSpeakPreference(Context context) {
        super(context);
    }

    public void setFragment(@NonNull SettingsFragment fragment) {
        global = (Global) fragment.requireActivity().getApplication();
        refreshSummary();
        setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                showDialog();
                return true;
            }
        });
    }

    private void showDialog() {
        final String current = currentMode();
        int checked = 2;
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i].equals(current)) {
                checked = i;
                break;
            }
        }
        CharSequence[] entries = new CharSequence[] {
                getContext().getString(R.string.preference_simultaneous_auto_speak_on),
                getContext().getString(R.string.preference_simultaneous_auto_speak_off),
                getContext().getString(R.string.preference_simultaneous_auto_speak_auto)
        };
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.preference_title_simultaneous_auto_speak)
                .setSingleChoiceItems(entries, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which >= 0 && which < VALUES.length && global != null) {
                            SharedPreferences.Editor editor = global.getSharedPreferences("default", Context.MODE_PRIVATE).edit();
                            editor.putString(SimultaneousAutoSpeak.KEY, VALUES[which]);
                            editor.apply();
                            refreshSummary();
                        }
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshSummary() {
        String mode = currentMode();
        int label;
        if (SimultaneousAutoSpeak.ON.equals(mode)) {
            label = R.string.preference_simultaneous_auto_speak_on;
        } else if (SimultaneousAutoSpeak.OFF.equals(mode)) {
            label = R.string.preference_simultaneous_auto_speak_off;
        } else {
            label = R.string.preference_simultaneous_auto_speak_auto;
        }
        setSummary(getContext().getString(R.string.preference_description_simultaneous_auto_speak, getContext().getString(label)));
    }

    private String currentMode() {
        if (global == null) {
            return SimultaneousAutoSpeak.AUTO;
        }
        return SimultaneousAutoSpeak.normalize(global.getSharedPreferences("default", Context.MODE_PRIVATE).getString(SimultaneousAutoSpeak.KEY, SimultaneousAutoSpeak.AUTO));
    }
}
