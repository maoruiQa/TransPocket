package nie.translator.rtranslator.tools;

/** Receives PCM as a synthesizer produces it. Return false to stop. */
public interface PcmSink {
    boolean onSamples(float[] samples);
}
