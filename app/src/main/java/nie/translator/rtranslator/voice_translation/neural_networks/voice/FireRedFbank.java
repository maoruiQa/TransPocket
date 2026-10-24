package nie.translator.rtranslator.voice_translation.neural_networks.voice;

/**
 * Kaldi-style 80-bin log-mel front end for FireRedVAD. The model was trained
 * on kaldi_native_fbank with 25 ms frames, a 10 ms shift, a Povey window,
 * 0.97 pre-emphasis, DC removal, power spectrum and log energies floored at
 * FLT_EPSILON. Samples are the raw 16-bit values, not a [-1, 1] scale: the CMVN
 * statistics shipped with the model are in that scale.
 * <p>
 * The class keeps no audio state; FireRedClauseVad owns the overlapping
 * sample window and calls {@link #compute(short[], int, float[], int)} for
 * each frame.
 */
final class FireRedFbank {
    static final int SAMPLE_RATE = 16000;
    static final int FRAME_LENGTH = 400;
    static final int FRAME_SHIFT = 160;
    static final int FFT_POINTS = 512;
    static final int MEL_BINS = 80;

    private static final double PREEMPHASIS = 0.97;
    /** Kaldi floors the mel energy at FLT_EPSILON, not 1e-20. Only digital silence reaches it. */
    private static final double MEL_ENERGY_FLOOR = 1.1920928955078125e-7;
    private static final int NUM_FFT_BINS = FFT_POINTS / 2;

    private final double[] window = new double[FRAME_LENGTH];
    private final int[] filterFirst = new int[MEL_BINS];
    private final double[][] filterWeights = new double[MEL_BINS][];
    private final double[] frame = new double[FRAME_LENGTH];
    private final double[] real = new double[FFT_POINTS];
    private final double[] imag = new double[FFT_POINTS];
    private final double[] power = new double[NUM_FFT_BINS];
    private final int[] bitReverse = new int[FFT_POINTS];
    private final double[] cosTable = new double[FFT_POINTS / 2];
    private final double[] sinTable = new double[FFT_POINTS / 2];

    FireRedFbank() {
        for (int i = 0; i < FRAME_LENGTH; i++) {
            window[i] = Math.pow(0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FRAME_LENGTH - 1)), 0.85);
        }
        buildMelFilters();
        for (int i = 0; i < FFT_POINTS; i++) {
            bitReverse[i] = Integer.reverse(i) >>> (32 - 9);
        }
        for (int i = 0; i < FFT_POINTS / 2; i++) {
            cosTable[i] = Math.cos(2.0 * Math.PI * i / FFT_POINTS);
            sinTable[i] = Math.sin(2.0 * Math.PI * i / FFT_POINTS);
        }
    }

    /**
     * Writes the 80 log-mel values of one frame at {@code out[outOffset]}.
     * CMVN is not applied here.
     */
    void compute(short[] samples, int offset, float[] out, int outOffset) {
        double mean = 0;
        for (int i = 0; i < FRAME_LENGTH; i++) {
            mean += samples[offset + i];
        }
        mean /= FRAME_LENGTH;
        for (int i = 0; i < FRAME_LENGTH; i++) {
            frame[i] = samples[offset + i] - mean;
        }
        // Kaldi pre-emphasises inside each frame, so the first sample is scaled down.
        for (int i = FRAME_LENGTH - 1; i > 0; i--) {
            frame[i] -= PREEMPHASIS * frame[i - 1];
        }
        frame[0] -= PREEMPHASIS * frame[0];

        for (int i = 0; i < FRAME_LENGTH; i++) {
            real[i] = frame[i] * window[i];
            imag[i] = 0;
        }
        for (int i = FRAME_LENGTH; i < FFT_POINTS; i++) {
            real[i] = 0;
            imag[i] = 0;
        }
        fft();
        for (int i = 0; i < NUM_FFT_BINS; i++) {
            power[i] = real[i] * real[i] + imag[i] * imag[i];
        }
        for (int bin = 0; bin < MEL_BINS; bin++) {
            double energy = 0;
            int first = filterFirst[bin];
            double[] weights = filterWeights[bin];
            for (int k = 0; k < weights.length; k++) {
                energy += weights[k] * power[first + k];
            }
            out[outOffset + bin] = (float) Math.log(Math.max(energy, MEL_ENERGY_FLOOR));
        }
    }

    private void buildMelFilters() {
        double melLow = melScale(20.0);
        double melHigh = melScale(SAMPLE_RATE / 2.0);
        double delta = (melHigh - melLow) / (MEL_BINS + 1);
        double binWidth = (double) SAMPLE_RATE / FFT_POINTS;
        for (int bin = 0; bin < MEL_BINS; bin++) {
            double left = melLow + bin * delta;
            double center = melLow + (bin + 1) * delta;
            double right = melLow + (bin + 2) * delta;
            int first = -1;
            int last = -1;
            double[] row = new double[NUM_FFT_BINS];
            for (int i = 0; i < NUM_FFT_BINS; i++) {
                double mel = melScale(i * binWidth);
                if (mel > left && mel < right) {
                    if (mel <= center) {
                        row[i] = (mel - left) / (center - left);
                    } else {
                        row[i] = (right - mel) / (right - center);
                    }
                    if (first < 0) {
                        first = i;
                    }
                    last = i;
                }
            }
            if (first < 0) {
                filterFirst[bin] = 0;
                filterWeights[bin] = new double[0];
                continue;
            }
            filterFirst[bin] = first;
            filterWeights[bin] = new double[last - first + 1];
            System.arraycopy(row, first, filterWeights[bin], 0, filterWeights[bin].length);
        }
    }

    private static double melScale(double frequency) {
        return 1127.0 * Math.log(1.0 + frequency / 700.0);
    }

    private void fft() {
        for (int i = 0; i < FFT_POINTS; i++) {
            int j = bitReverse[i];
            if (j > i) {
                double value = real[i];
                real[i] = real[j];
                real[j] = value;
                value = imag[i];
                imag[i] = imag[j];
                imag[j] = value;
            }
        }
        for (int length = 2; length <= FFT_POINTS; length <<= 1) {
            int half = length >> 1;
            int step = FFT_POINTS / length;
            for (int start = 0; start < FFT_POINTS; start += length) {
                for (int k = 0; k < half; k++) {
                    double wr = cosTable[k * step];
                    double wi = -sinTable[k * step];
                    int a = start + k;
                    int b = a + half;
                    double xr = real[b];
                    double xi = imag[b];
                    double tr = xr * wr - xi * wi;
                    double ti = xr * wi + xi * wr;
                    real[b] = real[a] - tr;
                    imag[b] = imag[a] - ti;
                    real[a] += tr;
                    imag[a] += ti;
                }
            }
        }
    }
}
