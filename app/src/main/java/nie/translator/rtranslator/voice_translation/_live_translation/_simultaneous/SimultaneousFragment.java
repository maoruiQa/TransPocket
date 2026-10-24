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

package nie.translator.rtranslator.voice_translation._live_translation._simultaneous;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.settings.SettingsActivity;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.tools.ThemeManager;
import nie.translator.rtranslator.tools.Tools;
import nie.translator.rtranslator.tools.gui.ButtonMic;
import nie.translator.rtranslator.tools.gui.ButtonSound;
import nie.translator.rtranslator.tools.gui.DeactivableButton;
import nie.translator.rtranslator.tools.gui.GuiTools;
import nie.translator.rtranslator.tools.gui.messages.GuiMessage;
import nie.translator.rtranslator.tools.gui.messages.MessagesAdapter;
import nie.translator.rtranslator.tools.services_communication.ServiceCommunicator;
import nie.translator.rtranslator.tools.services_communication.ServiceCommunicatorListener;
import nie.translator.rtranslator.voice_translation.VoiceTranslationActivity;
import nie.translator.rtranslator.voice_translation.VoiceTranslationFragment;
import nie.translator.rtranslator.voice_translation.VoiceTranslationService;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recorder;

public class SimultaneousFragment extends VoiceTranslationFragment {
    private ConstraintLayout container;
    private ButtonMic microphone;
    private ButtonSound sound;
    private MaterialButton sourceLanguageSelector;
    private MaterialButton targetLanguageSelector;
    private AppCompatImageButton exitButton;
    private AppCompatImageButton settingsButton;
    private Handler handler = new Handler();
    private SimultaneousService.SimultaneousServiceCommunicator serviceCommunicator;
    private VoiceTranslationService.VoiceTranslationServiceCallback serviceCallback;
    /** False until translation resources and the microphone are both ready. Speech before that is not captured. */
    private boolean listeningReady;

    public SimultaneousFragment() {
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        serviceCommunicator = new SimultaneousService.SimultaneousServiceCommunicator(0);
        serviceCallback = new SimultaneousServiceCallback();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_simultaneous, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        container = view.findViewById(R.id.container);
        sourceLanguageSelector = view.findViewById(R.id.first_lang_selector);
        targetLanguageSelector = view.findViewById(R.id.second_lang_selector);
        exitButton = view.findViewById(R.id.exitButton);
        settingsButton = view.findViewById(R.id.settingsButton);
        sound = view.findViewById(R.id.soundButton);
        microphone = view.findViewById(R.id.buttonMic);
        microphone.initialize(this, view.findViewById(R.id.leftLine), view.findViewById(R.id.centerLine), view.findViewById(R.id.rightLine));
        showPreparing();
        deactivateInputs(DeactivableButton.DEACTIVATED);
        // The first deactivate only records the color. The button's layout tint still looks live, so apply it once more.
        microphone.deactivate(DeactivableButton.DEACTIVATED);
        view.setAccessibilityPaneTitle(getString(R.string.title_fragment_simultaneous));
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Toolbar toolbar = activity.findViewById(R.id.toolbarSimultaneous);
        activity.setActionBar(toolbar);
        WindowInsets windowInsets = activity.getFragmentContainer().getRootWindowInsets();
        if (windowInsets != null) {
            container.dispatchApplyWindowInsets(windowInsets.replaceSystemWindowInsets(
                    windowInsets.getSystemWindowInsetLeft(),
                    windowInsets.getSystemWindowInsetTop(),
                    windowInsets.getSystemWindowInsetRight(),
                    0));
        }
        sourceLanguageSelector.setText(global.getSimultaneousSourceLanguage(true).getDisplayNameWithoutTTS());
        targetLanguageSelector.setText(global.getSimultaneousTargetLanguage(true).getDisplayNameWithoutTTS());

        View.OnClickListener deactivatedClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!listeningReady) {
                    Toast.makeText(activity, R.string.simultaneous_preparing, Toast.LENGTH_SHORT).show();
                }
            }
        };
        View.OnClickListener micMissingClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.showPermissionDialog(VoiceTranslationActivity.SIMULTANEOUS_FRAGMENT);
            }
        };
        exitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.onBackPressed();
            }
        });
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(activity, SettingsActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        });
        sound.setOnClickListenerForDeactivated(deactivatedClickListener);
        sound.setOnClickListenerForTTSError(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(global, R.string.error_tts_toast, Toast.LENGTH_SHORT).show();
            }
        });
        sound.setOnClickListenerForActivated(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (sound.isMute()) {
                    sound.setMute(false);
                    serviceCommunicator.startSound();
                } else {
                    sound.setMute(true);
                    serviceCommunicator.stopSound();
                }
            }
        });
        microphone.setOnClickListenerForActivated(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (microphone.getState() == ButtonMic.STATE_NORMAL) {
                    if (microphone.isMute()) {
                        startMicrophone(true);
                    } else {
                        stopMicrophone(true);
                    }
                }
            }
        });
        microphone.setOnClickListenerForDeactivatedForMissingMicPermission(micMissingClickListener);
        microphone.setOnClickListenerForDeactivated(deactivatedClickListener);
        if (!SimultaneousService.isRunning) {
            String autoSpeak = global.getSharedPreferences("default", Context.MODE_PRIVATE).getString(SimultaneousAutoSpeak.KEY, SimultaneousAutoSpeak.AUTO);
            sound.setMute(SimultaneousAutoSpeak.isMuted(autoSpeak, Recorder.headphonesConnected(activity)));
        }
    }

    @Override
    public void onStart() {
        listeningReady = false;
        resumeModelLoading();
        super.onStart();
        if (description != null && Tools.hasPermissions(activity, Global.REQUIRED_PERMISSIONS_VOICE)) {
            showPreparing();
            if (microphone != null) {
                microphone.deactivate(DeactivableButton.DEACTIVATED);
            }
        }
        if (getArguments() != null && getArguments().getBoolean("firstStart", false)) {
            getArguments().remove("firstStart");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    connectToService();
                }
            }, 300);
        } else {
            connectToService();
        }
    }

    @Override
    protected void connectToService() {
        activity.connectToSimultaneousService(serviceCallback, new ServiceCommunicatorListener() {
            @Override
            public void onServiceCommunicator(ServiceCommunicator communicator) {
                serviceCommunicator = (SimultaneousService.SimultaneousServiceCommunicator) communicator;
                restoreAttributesFromService();
                sourceLanguageSelector.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showLanguageListDialog(true);
                    }
                });
                targetLanguageSelector.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showLanguageListDialog(false);
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                SimultaneousFragment.super.onFailureConnectingWithService(reasons, value);
            }
        });
    }

    @Override
    public void restoreAttributesFromService() {
        serviceCommunicator.getAttributes(new VoiceTranslationService.AttributesListener() {
            @Override
            public void onSuccess(ArrayList<GuiMessage> messages, boolean isMicMute, boolean isAudioMute, boolean isTTSError, boolean isEditTextOpen, boolean isBluetoothHeadsetConnected, boolean isMicAutomatic, boolean isMicActivated, long speakingUtteranceId, int listeningMic) {
                mAdapter = new MessagesAdapter(messages, global, speakingUtteranceId, new MessagesAdapter.Callback() {
                    @Override
                    public void onFirstItemAdded() {
                        descriptionContainer.setVisibility(View.GONE);
                        mRecyclerView.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onTTSButtonClick(GuiMessage message, boolean play) {
                        if (play) {
                            serviceCommunicator.speakText(message.getMessage().getText(), message.getContentLanguage().getCode(), String.valueOf(message.getMessageID()));
                        } else {
                            serviceCommunicator.stopSpeakingText();
                        }
                    }
                }, true);
                mRecyclerView.setAdapter(mAdapter);
                microphone.setMute(isMicMute, false);
                if (isMicActivated && !isMicMute) {
                    if (listeningMic == VoiceTranslationService.AUTO_LANGUAGE) {
                        microphone.onVoiceStarted(false);
                    } else {
                        microphone.onVoiceEnded(false);
                    }
                } else {
                    microphone.onVoiceEnded(false);
                }
                sound.setMute(isAudioMute);
                if (isTTSError) {
                    sound.deactivate(DeactivableButton.DEACTIVATED_FOR_TTS_ERROR);
                }
                if (!Tools.hasPermissions(activity, Global.REQUIRED_PERMISSIONS_VOICE)) {
                    hideModelLoading();
                    description.setText(R.string.description_permission_simultaneous);
                    deactivateInputs(DeactivableButton.DEACTIVATED_FOR_MISSING_MIC_PERMISSION);
                } else if (!isMicActivated) {
                    showPreparing();
                    microphone.deactivate(DeactivableButton.DEACTIVATED);
                } else {
                    showReady();
                    if (!microphone.isActivated()) {
                        activateInputs(!microphone.isMute());
                    }
                }
            }
        });
    }

    @Override
    public void onMicPermissionResult(boolean granted) {
        if (!granted) {
            return;
        }
        // The service starts capture once the translation resources are ready. Starting here would turn the button on while the opening speech is still dropped.
        startMicrophone(false);
        if (!listeningReady) {
            showPreparing();
        }
    }

    private void showPreparing() {
        listeningReady = false;
        resumeModelLoading();
        showModelLoading(0, 100);
        if (microphone != null) {
            microphone.setContentDescription(getString(R.string.simultaneous_preparing));
        }
    }

    private void showReady() {
        if (description == null) {
            return;
        }
        finishModelLoading();
        CharSequence previous = description.getText();
        description.setText(R.string.simultaneous_ready);
        description.setTextColor(ThemeManager.getColor(description.getContext(), R.attr.xtColorPrimaryVeryDark));
        if (microphone != null) {
            microphone.setContentDescription(getString(R.string.description_start_automatic_recognition));
        }
        if (!listeningReady) {
            listeningReady = true;
            if (!TextUtils.equals(previous, description.getText())) {
                description.announceForAccessibility(description.getText());
            }
        }
    }

    @Override
    public void startMicrophone(boolean changeAspect) {
        if (changeAspect) {
            microphone.setMute(false);
        }
        serviceCommunicator.startMic();
    }

    @Override
    public void stopMicrophone(boolean changeAspect) {
        if (changeAspect) {
            microphone.setMute(true);
        }
        serviceCommunicator.stopMic(changeAspect);
    }

    @Override
    protected void deactivateInputs(int cause) {
        microphone.deactivate(cause);
        if (cause == DeactivableButton.DEACTIVATED) {
            sound.deactivate(DeactivableButton.DEACTIVATED);
        } else {
            sound.activate(false);
        }
    }

    @Override
    protected void activateInputs(boolean start) {
        microphone.activate(start);
        sound.activate(false);
    }

    private void showLanguageListDialog(final boolean source) {
        final ArrayList<CustomLocale> languages = global.getLanguages(Global.RTranslatorMode.SIMULTANEOUS_MODE, true);
        CustomLocale selected = source ? global.getSimultaneousSourceLanguage(false) : global.getSimultaneousTargetLanguage(false);
        String title = getString(source ? R.string.dialog_select_source_language : R.string.dialog_select_target_language);
        GuiTools.showLanguageListDialog(activity, title, languages, selected, true, new GuiTools.OnLanguageClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id, CustomLocale item) {
                if (item == null || !languages.contains(item)) {
                    return;
                }
                if (source) {
                    global.setSimultaneousSourceLanguage(item);
                    serviceCommunicator.changeSourceLanguage(item);
                    sourceLanguageSelector.setText(item.getDisplayNameWithoutTTS());
                } else {
                    global.setSimultaneousTargetLanguage(item);
                    serviceCommunicator.changeTargetLanguage(item);
                    targetLanguageSelector.setText(item.getDisplayNameWithoutTTS());
                }
            }
        });
    }

    @Override
    public void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        sourceLanguageSelector.setOnClickListener(null);
        targetLanguageSelector.setOnClickListener(null);
        activity.disconnectFromSimultaneousService(serviceCommunicator);
    }

    public class SimultaneousServiceCallback extends VoiceTranslationService.VoiceTranslationServiceCallback {
        @Override
        public void onTTSStarted(String utteranceId) {
            super.onTTSStarted(utteranceId);
            if (utteranceId != null && mAdapter != null) {
                mAdapter.setPlayingMessageID(Long.parseLong(utteranceId));
            }
        }

        @Override
        public void onTTSDone(String utteranceId) {
            super.onTTSDone(utteranceId);
            if (mAdapter != null) {
                mAdapter.setPlayingMessageID(-1);
            }
            if (!microphone.isMute() && !microphone.isActivated()) {
                microphone.activate(false);
            }
        }

        @Override
        public void onVoiceStarted(int mode) {
            super.onVoiceStarted(mode);
            if (!microphone.isMute()) {
                microphone.onVoiceStarted(true);
            }
        }

        @Override
        public void onVoiceEnded() {
            super.onVoiceEnded();
            microphone.onVoiceEnded(true);
        }

        @Override
        public void onVolumeLevel(float volumeLevel) {
            super.onVolumeLevel(volumeLevel);
            if (microphone.isListening()) {
                microphone.updateVolumeLevel(volumeLevel);
            }
        }

        @Override
        public void onModelLoading(int progress, int max) {
            if (!listeningReady && Tools.hasPermissions(activity, Global.REQUIRED_PERMISSIONS_VOICE)) {
                showModelLoading(progress, max);
                if (microphone != null) {
                    microphone.deactivate(DeactivableButton.DEACTIVATED);
                }
            }
        }

        @Override
        public void onMicActivated() {
            super.onMicActivated();
            showReady();
            if (sound.getActivationStatus() != DeactivableButton.DEACTIVATED_FOR_TTS_ERROR) {
                sound.activate(false);
            }
            if (!microphone.isActivated()) {
                microphone.activate(false);
            }
        }

        @Override
        public void onMicDeactivated() {
            super.onMicDeactivated();
            // A spoken translation used to deactivate this button and leave it dark after the utterance.
            if (!microphone.isMute() && microphone.getState() == ButtonMic.STATE_NORMAL && !microphone.isActivated()) {
                microphone.activate(false);
            }
        }

        @Override
        public void onMessage(GuiMessage message) {
            super.onMessage(message);
            if (message == null || mAdapter == null || mRecyclerView == null) {
                return;
            }
            int messageIndex = mAdapter.getMessageIndex(message.getMessageID());
            if (messageIndex != -1) {
                if ((!mRecyclerView.isAnimating() && !mRecyclerView.getLayoutManager().isSmoothScrolling()) || message.isFinal()) {
                    if (message.isFinal() && mRecyclerView.getItemAnimator() != null) {
                        mRecyclerView.getItemAnimator().endAnimations();
                    }
                    mAdapter.setMessage(messageIndex, message);
                }
            } else {
                if (mRecyclerView.getItemAnimator() != null) {
                    mRecyclerView.getItemAnimator().endAnimations();
                }
                mAdapter.addMessage(message);
                if (((LinearLayoutManager) mRecyclerView.getLayoutManager()).findLastVisibleItemPosition() == mAdapter.getItemCount() - 2) {
                    mRecyclerView.smoothScrollToPosition(mAdapter.getItemCount() - 1);
                }
            }
        }

        @Override
        public void onError(int[] reasons, long value) {
            for (int aReason : reasons) {
                switch (aReason) {
                    case ErrorCodes.SAFETY_NET_EXCEPTION:
                    case ErrorCodes.MISSED_CONNECTION:
                        activity.showInternetLackDialog(R.string.error_internet_lack_services, null);
                        break;
                    case ErrorCodes.MISSING_GOOGLE_TTS:
                    case ErrorCodes.GOOGLE_TTS_ERROR:
                        sound.deactivate(DeactivableButton.DEACTIVATED_FOR_TTS_ERROR);
                        break;
                    default:
                        activity.onError(aReason, value);
                        break;
                }
            }
        }
    }
}
