package nie.translator.rtranslator.tools;

import android.content.Context;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Speaks one sentence with Fun-CosyVoice 3 only. Kept separate so the
 * recognizer classes, which load ONNX Runtime, are not initialized first.
 */
@RunWith(AndroidJUnit4.class)
public class CosyOnlyProbeTest {
    @Test
    public void speak() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        Log.i("voice_probe", "cosy-only start");
        File dir = LiveDraftVoices.modelDir(context, LiveDraftVoices.ENGINE_COSYVOICE);
        boolean ready = LiveDraftVoices.isReady(context, LiveDraftVoices.ENGINE_COSYVOICE);
        boolean runtime = CosyVoiceQuant.available();
        Log.i("voice_probe", "cosy ready=" + ready + " runtime=" + runtime + " dir=" + dir);
        if (!ready || !runtime) {
            throw new AssertionError("cosy not ready runtime=" + runtime);
        }
        CosyVoiceQuant.Session session = new CosyVoiceQuant.Session(dir);
        try {
            String text = CosyVoiceQuant.sampleSentence("zh");
            session.prepare("zh", LiveDraftVoices.GENDER_FEMALE);
            final float[][] parts = new float[1][];
            final int[] used = new int[1];
            session.synthesize(text, new PcmSink() {
                @Override
                public boolean onSamples(float[] samples) {
                    if (samples == null || samples.length == 0) {
                        return true;
                    }
                    float[] joined = new float[used[0] + samples.length];
                    if (parts[0] != null) {
                        System.arraycopy(parts[0], 0, joined, 0, used[0]);
                    }
                    System.arraycopy(samples, 0, joined, used[0], samples.length);
                    parts[0] = joined;
                    used[0] = joined.length;
                    Log.i("voice_probe", "cosy chunk " + samples.length + " total " + used[0]);
                    return used[0] < CosyVoiceQuant.SAMPLE_RATE * 8;
                }
            });
            float[] pcm = parts[0] == null ? new float[0] : parts[0];
            double energy = 0;
            float peak = 0f;
            for (float sample : pcm) {
                energy += sample * (double) sample;
                peak = Math.max(peak, Math.abs(sample));
            }
            double rms = pcm.length == 0 ? 0 : Math.sqrt(energy / pcm.length);
            Log.i("voice_probe", "cosy samples=" + pcm.length + " rms=" + rms + " peak=" + peak);
            File out = new File(context.getFilesDir(), "probe");
            if (!out.isDirectory()) {
                out.mkdirs();
            }
            File wav = new File(out, "cosy-zh.wav");
            writeWav(wav, pcm);
            if (pcm.length < CosyVoiceQuant.SAMPLE_RATE / 2 || rms < 0.01) {
                throw new AssertionError("cosy silent samples=" + pcm.length + " rms=" + rms);
            }
        } finally {
            session.close();
        }
    }

    private static void writeWav(File file, float[] samples) throws java.io.IOException {
        int dataBytes = samples.length * 2;
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(44 + dataBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'});
        buffer.putInt(36 + dataBytes);
        buffer.put(new byte[]{'W', 'A', 'V', 'E'});
        buffer.put(new byte[]{'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(CosyVoiceQuant.SAMPLE_RATE);
        buffer.putInt(CosyVoiceQuant.SAMPLE_RATE * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put(new byte[]{'d', 'a', 't', 'a'});
        buffer.putInt(dataBytes);
        for (float sample : samples) {
            if (sample > 1f) {
                sample = 1f;
            } else if (sample < -1f) {
                sample = -1f;
            }
            buffer.putShort((short) (sample * 32767f));
        }
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(buffer.array());
        } finally {
            output.close();
        }
    }
}
