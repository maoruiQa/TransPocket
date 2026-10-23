package nie.translator.rtranslator.voice_translation.neural_networks.voice;

/**
 * Maps the microphone-sensitivity slider onto the 16-bit peak gate.
 * 0–100 keeps the historical curve. 100 stays the old maximum (gate 400).
 * Above 100 the gate halves every 100 points. 400 is gate 50, about -56 dBFS.
 * Below that the phone noise floor holds an utterance open.
 */
public final class MicSensitivity {
    public static final int DEFAULT = 100;
    /** Slider limit while extended sensitivity is off. */
    public static final int STANDARD_MAX = 100;
    /** Slider limit while extended sensitivity is on. */
    public static final int EXTENDED_MAX = 400;
    public static final int MAX_AMPLITUDE_THRESHOLD = 15000;
    public static final int DEFAULT_AMPLITUDE_THRESHOLD = 1500;
    public static final int MIN_AMPLITUDE_THRESHOLD = 400;
    /** Gate at {@link #EXTENDED_MAX}: three halvings from the gate at 100. */
    public static final int EXTENDED_MIN_AMPLITUDE_THRESHOLD = MIN_AMPLITUDE_THRESHOLD / 8;

    private MicSensitivity() {
    }

    /** Clamps into the extended range, which is the full range the curve supports. */
    public static int clamp(int sensitivity) {
        return clamp(sensitivity, true);
    }

    public static int clamp(int sensitivity, boolean extended) {
        int max = extended ? EXTENDED_MAX : STANDARD_MAX;
        if (sensitivity < 0) {
            return 0;
        }
        if (sensitivity > max) {
            return max;
        }
        return sensitivity;
    }

    public static int amplitudeThreshold(int sensitivity) {
        int value = clamp(sensitivity);
        if (value <= DEFAULT) {
            float amplitudePercentage = 1f - (value / 100f);
            if (amplitudePercentage < 0.5f) {
                return Math.round(MIN_AMPLITUDE_THRESHOLD + ((DEFAULT_AMPLITUDE_THRESHOLD - MIN_AMPLITUDE_THRESHOLD) * (amplitudePercentage * 2)));
            }
            return Math.round(DEFAULT_AMPLITUDE_THRESHOLD + ((MAX_AMPLITUDE_THRESHOLD - DEFAULT_AMPLITUDE_THRESHOLD) * ((amplitudePercentage - 0.5f) * 2)));
        }
        double halvings = (value - DEFAULT) / 100.0;
        int threshold = (int) Math.round(MIN_AMPLITUDE_THRESHOLD * Math.pow(0.5, halvings));
        if (threshold < EXTENDED_MIN_AMPLITUDE_THRESHOLD) {
            return EXTENDED_MIN_AMPLITUDE_THRESHOLD;
        }
        return threshold;
    }
}
