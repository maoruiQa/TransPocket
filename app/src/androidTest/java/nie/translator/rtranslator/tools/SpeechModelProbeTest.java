package nie.translator.rtranslator.tools;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import nie.translator.rtranslator.voice_translation.neural_networks.voice.Qwen3AsrEngine;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.WhisperTurboEngine;

/**
 * Speaks a fixed sentence with Fun-CosyVoice 3, then transcribes that
 * clip with Qwen3-ASR 0.6B and Whisper large-v3-turbo. Skips when the models
 * are not in app storage.
 */
@RunWith(AndroidJUnit4.class)
public class SpeechModelProbeTest {
    private static final String TAG = "voice_probe";

    @Test
    public void speakThenTranscribe() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        File report = new File(context.getFilesDir(), "voice-probe.txt");
        StringBuilder log = new StringBuilder();
        File cosyDir = LiveDraftVoices.modelDir(context, LiveDraftVoices.ENGINE_COSYVOICE);
        File qwenDir = new File(context.getFilesDir(), "ASR/Qwen3-ASR-0.6B");
        File turboDir = new File(context.getFilesDir(), "ASR/Whisper-Large-V3-Turbo");
        boolean cosyReady = LiveDraftVoices.isReady(context, LiveDraftVoices.ENGINE_COSYVOICE);
        boolean qwenReady = new File(qwenDir, "encoder.int4.onnx").isFile() && new File(qwenDir, "decoder_weights.int4.data").isFile();
        boolean turboReady = new File(turboDir, "encoder_model_q4.onnx").isFile() && new File(turboDir, "decoder_model_merged_q4.onnx").isFile();
        line(log, "cosyReady=" + cosyReady + " dir=" + cosyDir);
        line(log, "qwenReady=" + qwenReady + " dir=" + qwenDir);
        line(log, "turboReady=" + turboReady + " dir=" + turboDir);
        line(log, "cosyRuntime=" + CosyVoiceQuant.available());

        File probeDir = new File(context.getFilesDir(), "probe");
        if (!probeDir.isDirectory() && !probeDir.mkdirs()) {
            line(log, "could not create " + probeDir);
        }
        float[] cosyPcm = null;
        if (cosyReady && CosyVoiceQuant.available()) {
            cosyPcm = speakCosy(log, cosyDir, probeDir);
        } else {
            line(log, "cosy skipped");
        }
        float[] speech = null;
        int speechRate = 16000;
        if (cosyPcm != null && cosyPcm.length > 0) {
            speech = resample(cosyPcm, CosyVoiceQuant.SAMPLE_RATE, 16000);
            line(log, "asr source=cosy samples=" + speech.length);
        } else {
            File fallback = new File(probeDir, "fallback.wav");
            if (fallback.isFile()) {
                WavClip clip = readWav(fallback);
                speech = resample(clip.samples, clip.rate, 16000);
                line(log, "asr source=fallback rate=" + clip.rate + " samples=" + speech.length);
            } else {
                line(log, "FAIL no audio for recognition");
            }
        }
        if (speech != null && speech.length > 0) {
            line(log, "asr sec=" + (speech.length / (float) speechRate));
            if (qwenReady) {
                transcribeQwen(log, qwenDir, speech);
            }
            if (turboReady) {
                transcribeTurbo(log, turboDir, speech);
            }
        }
        write(report, log.toString());
        Log.i(TAG, log.toString());
        if (log.indexOf("FAIL") >= 0) {
            throw new AssertionError(log.toString());
        }
    }

    private static float[] speakCosy(StringBuilder log, File dir, File probeDir) {
        CosyVoiceQuant.Session session = null;
        try {
            session = new CosyVoiceQuant.Session(dir);
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
                    return used[0] < CosyVoiceQuant.SAMPLE_RATE * 12;
                }
            });
            float[] pcm = parts[0] == null ? new float[0] : parts[0];
            Stats stats = stats(pcm);
            line(log, "cosy samples=" + pcm.length + " sec=" + (pcm.length / (float) CosyVoiceQuant.SAMPLE_RATE) + " rms=" + stats.rms + " peak=" + stats.peak);
            if (pcm.length < CosyVoiceQuant.SAMPLE_RATE / 2 || stats.rms < 0.01f) {
                line(log, "FAIL cosy produced no usable audio");
            } else {
                File wav = new File(probeDir, "cosy-zh.wav");
                writeWav(wav, pcm, CosyVoiceQuant.SAMPLE_RATE);
                play(pcm, CosyVoiceQuant.SAMPLE_RATE);
                line(log, "cosy played " + wav.getAbsolutePath());
            }
            return pcm;
        } catch (Throwable error) {
            line(log, "FAIL cosy " + error);
            Log.e(TAG, "cosy", error);
            return null;
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    private static void transcribeQwen(StringBuilder log, File dir, float[] speech) {
        Qwen3AsrEngine engine = null;
        try {
            long start = android.os.SystemClock.elapsedRealtime();
            engine = new Qwen3AsrEngine(dir, false);
            Qwen3AsrEngine.Transcript transcript = engine.transcribe(speech);
            long elapsed = android.os.SystemClock.elapsedRealtime() - start;
            line(log, "qwen06 ms=" + elapsed + " lang=" + transcript.languageCode + " text=" + transcript.text);
            if (transcript.text == null || transcript.text.trim().length() < 2) {
                line(log, "FAIL qwen06 empty transcript");
            }
        } catch (Throwable error) {
            line(log, "FAIL qwen06 " + error);
            Log.e(TAG, "qwen", error);
        } finally {
            if (engine != null) {
                engine.close();
            }
        }
    }

    private static void transcribeTurbo(StringBuilder log, File dir, float[] speech) {
        WhisperTurboEngine engine = null;
        try {
            long start = android.os.SystemClock.elapsedRealtime();
            engine = new WhisperTurboEngine(dir, "encoder_model_q4.onnx", "decoder_model_merged_q4.onnx", 128, 20);
            WhisperTurboEngine.Transcripts transcripts = engine.transcribe(speech, "en", null, false);
            long elapsed = android.os.SystemClock.elapsedRealtime() - start;
            line(log, "turbo ms=" + elapsed + " text=" + transcripts.firstText + " score=" + transcripts.firstScore);
            if (transcripts.firstText == null || transcripts.firstText.trim().length() < 2) {
                line(log, "FAIL turbo empty transcript");
            }
        } catch (Throwable error) {
            line(log, "FAIL turbo " + error);
            Log.e(TAG, "turbo", error);
        } finally {
            if (engine != null) {
                engine.close();
            }
        }
    }

    private static WavClip readWav(File file) throws java.io.IOException {
        byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != 0x46464952 || buffer.getInt() < 0 || buffer.getInt() != 0x45564157) {
            throw new java.io.IOException("not wav");
        }
        int rate = 16000;
        int channels = 1;
        int bits = 16;
        byte[] pcm = null;
        while (buffer.remaining() >= 8) {
            int id = buffer.getInt();
            int size = buffer.getInt();
            int start = buffer.position();
            if (id == 0x20746d66 && size >= 16) {
                buffer.getShort();
                channels = buffer.getShort() & 0xffff;
                rate = buffer.getInt();
                buffer.getInt();
                buffer.getShort();
                bits = buffer.getShort() & 0xffff;
            } else if (id == 0x61746164) {
                pcm = new byte[Math.min(size, buffer.remaining())];
                buffer.get(pcm);
            }
            buffer.position(Math.min(buffer.limit(), start + size + (size & 1)));
        }
        if (pcm == null) {
            throw new java.io.IOException("wav has no data");
        }
        int bytesPerSample = Math.max(1, bits / 8);
        int frames = pcm.length / Math.max(1, channels * bytesPerSample);
        float[] samples = new float[frames];
        java.nio.ByteBuffer audio = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) {
            float sum = 0f;
            for (int channel = 0; channel < channels; channel++) {
                if (bits == 16) {
                    sum += audio.getShort() / 32768f;
                } else {
                    sum += (audio.get() & 0xff) / 128f - 1f;
                }
            }
            samples[frame] = sum / channels;
        }
        return new WavClip(samples, rate);
    }

    private static float[] resample(float[] input, int from, int to) {
        if (input == null || input.length == 0 || from == to) {
            return input;
        }
        int out = Math.max(1, (int) ((long) input.length * to / from));
        float[] result = new float[out];
        double step = (double) from / to;
        for (int i = 0; i < out; i++) {
            double position = i * step;
            int left = (int) position;
            int right = Math.min(left + 1, input.length - 1);
            float mix = (float) (position - left);
            result[i] = input[left] * (1f - mix) + input[right] * mix;
        }
        return result;
    }

    private static void play(float[] samples, int sampleRate) {
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float sample = samples[i];
            if (sample > 1f) {
                sample = 1f;
            } else if (sample < -1f) {
                sample = -1f;
            }
            pcm[i] = (short) (sample * 32767f);
        }
        int bytes = pcm.length * 2;
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(bytes, AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        try {
            track.write(pcm, 0, pcm.length);
            track.play();
            Thread.sleep(Math.min(8000L, samples.length * 1000L / sampleRate + 300L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            track.release();
        }
    }

    private static Stats stats(float[] samples) {
        double energy = 0;
        float peak = 0f;
        if (samples != null) {
            for (float sample : samples) {
                energy += sample * (double) sample;
                float abs = Math.abs(sample);
                if (abs > peak) {
                    peak = abs;
                }
            }
        }
        int count = samples == null ? 0 : samples.length;
        return new Stats(count == 0 ? 0 : Math.sqrt(energy / count), peak);
    }

    private static void writeWav(File file, float[] samples, int sampleRate) throws java.io.IOException {
        int dataBytes = samples.length * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'});
        buffer.putInt(36 + dataBytes);
        buffer.put(new byte[]{'W', 'A', 'V', 'E'});
        buffer.put(new byte[]{'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * 2);
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

    private static void write(File file, String text) throws java.io.IOException {
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            output.close();
        }
    }

    private static void line(StringBuilder log, String text) {
        log.append(text).append('\n');
        Log.i(TAG, text);
    }

    private static final class WavClip {
        final float[] samples;
        final int rate;

        WavClip(float[] samples, int rate) {
            this.samples = samples;
            this.rate = rate;
        }
    }

    private static final class Stats {
        final double rms;
        final float peak;

        Stats(double rms, float peak) {
            this.rms = rms;
            this.peak = peak;
        }
    }
}
