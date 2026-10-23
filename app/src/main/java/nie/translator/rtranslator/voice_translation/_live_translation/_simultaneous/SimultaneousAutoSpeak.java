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

/**
 * How simultaneous interpretation decides whether to read translations aloud
 * when a session starts. The toolbar button overrides this until the session ends.
 */
public final class SimultaneousAutoSpeak {
    public static final String KEY = "simultaneousAutoSpeak";
    public static final String ON = "on";
    public static final String OFF = "off";
    /** Headphones or another headset output: on. Phone speaker: off. */
    public static final String AUTO = "auto";

    private SimultaneousAutoSpeak() {
    }

    public static String normalize(String mode) {
        if (ON.equals(mode) || OFF.equals(mode)) {
            return mode;
        }
        return AUTO;
    }

    /** True when translations should not be spoken at the start of a session. */
    public static boolean isMuted(String mode, boolean headphonesConnected) {
        String normalized = normalize(mode);
        if (ON.equals(normalized)) {
            return false;
        }
        if (OFF.equals(normalized)) {
            return true;
        }
        return !headphonesConnected;
    }
}
