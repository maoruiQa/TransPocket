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

package nie.translator.rtranslator.voice_translation._live_translation;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Toolbar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;

import nie.translator.rtranslator.R;
import nie.translator.rtranslator.voice_translation.VoiceTranslationActivity;

public class LiveTranslationFragment extends Fragment {
    private VoiceTranslationActivity activity;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_live_translation, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (VoiceTranslationActivity) requireActivity();
        Toolbar toolbar = view.findViewById(R.id.toolbarLiveTranslation);
        activity.setActionBar(toolbar);
        WindowInsets windowInsets = activity.getFragmentContainer().getRootWindowInsets();
        if (windowInsets != null && view instanceof ConstraintLayout) {
            view.dispatchApplyWindowInsets(windowInsets.replaceSystemWindowInsets(
                    windowInsets.getSystemWindowInsetLeft(),
                    windowInsets.getSystemWindowInsetTop(),
                    windowInsets.getSystemWindowInsetRight(),
                    0));
        }
        view.findViewById(R.id.exitButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.onBackPressed();
            }
        });
        view.findViewById(R.id.buttonConversationMode).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.setFragment(VoiceTranslationActivity.PAIRING_FRAGMENT, true);
            }
        });
        view.findViewById(R.id.buttonSimultaneousMode).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.setFragment(VoiceTranslationActivity.SIMULTANEOUS_FRAGMENT, true);
            }
        });
    }
}
