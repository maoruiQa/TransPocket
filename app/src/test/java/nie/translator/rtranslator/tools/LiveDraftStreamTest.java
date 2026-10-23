package nie.translator.rtranslator.tools;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LiveDraftStreamTest {
    @Test
    public void bufferKeepsAnUnfinishedWord() {
        LiveDraftBuffer buffer = new LiveDraftBuffer();
        buffer.add("Hel");
        assertNull(buffer.pull(false));
        assertEquals("Hel", buffer.pending());
        buffer.add("lo, world.");
        assertEquals("Hello, world.", buffer.pull(false));
        assertEquals("", buffer.pending());
        buffer.add(" Next");
        assertNull(buffer.pull(false));
        assertEquals(" Next", buffer.pull(true));
        assertEquals("", buffer.pending());
    }

    @Test
    public void bufferReleasesOneSentenceAtATime() {
        LiveDraftBuffer buffer = new LiveDraftBuffer();
        buffer.add("第一句。第二句。第三");
        assertEquals("第一句。", buffer.pull(false));
        assertEquals("第二句。", buffer.pull(false));
        assertNull(buffer.pull(false));
        assertEquals("第三", buffer.pull(true));
    }

    @Test
    public void bufferCutsASentenceThatExceedsTheCap() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            text.append('字');
        }
        text.append('。');
        text.append("下一句。");
        LiveDraftBuffer buffer = new LiveDraftBuffer();
        buffer.add(text.toString());
        String first = buffer.pull(false);
        assertEquals(SimultaneousText.SPEAK_CHUNK_CHARS, first.length());
        assertFalse(first.contains("下一"));
    }

    @Test
    public void playbackWaitsForAPrimeAndAShortClipStillStarts() {
        int rate = 24000;
        assertFalse(PcmPlayback.shouldStart(PcmPlayback.samplesFor(rate, 299), rate, false));
        assertTrue(PcmPlayback.shouldStart(PcmPlayback.samplesFor(rate, 300), rate, false));
        assertTrue(PcmPlayback.shouldStart(10, rate, true));
        assertFalse(PcmPlayback.shouldStart(0, rate, true));
    }

    @Test
    public void underrunPausesWithoutPadding() {
        int rate = 24000;
        assertTrue(PcmPlayback.shouldPause(PcmPlayback.samplesFor(rate, 59), rate, false));
        assertFalse(PcmPlayback.shouldPause(PcmPlayback.samplesFor(rate, 60), rate, false));
        assertFalse(PcmPlayback.shouldPause(0, rate, true));
        PcmQueue queue = new PcmQueue(rate);
        short[] samples = new short[]{1, 2, 3, 4};
        assertEquals(4, queue.offer(samples));
        short[] read = new short[8];
        assertEquals(4, queue.read(read));
        assertEquals(1, read[0]);
        assertEquals(4, read[3]);
        assertEquals(0, read[4]);
        assertEquals(0, queue.read(read));
        assertEquals(0, queue.queued());
    }

    @Test
    public void queueDropsSamplesOnCancelAndBlocksWhenAhead() throws Exception {
        PcmQueue queue = new PcmQueue(10);
        assertEquals(60, queue.offer(new short[60]));
        assertEquals(0, queue.offer(new short[1]));
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    queue.write(new short[]{9, 9, 9, 9, 9});
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        writer.start();
        Thread.sleep(80);
        assertTrue(writer.isAlive());
        short[] discarded = new short[25];
        assertEquals(25, queue.read(discarded));
        writer.join(1000);
        assertFalse(writer.isAlive());
        queue.cancel();
        assertEquals(0, queue.queued());
        assertEquals(-1, queue.read(discarded));
    }

    @Test
    public void fadeInKeepsTheSampleCount() {
        short[] pcm = new short[]{1000, 1000, 1000, 1000};
        PcmPlayback.fadeIn(pcm, pcm.length, 1000);
        assertEquals(4, pcm.length);
        assertTrue(pcm[0] < pcm[3]);
    }

    @Test
    public void tokenizerMergesAPair() {
        Map<String, Integer> vocab = new HashMap<>();
        vocab.put("a", 0);
        vocab.put("b", 1);
        vocab.put("ab", 2);
        ArrayList<Qwen2Tokenizer.Pair> merges = new ArrayList<>();
        merges.add(new Qwen2Tokenizer.Pair("a", "b"));
        Qwen2Tokenizer tokenizer = new Qwen2Tokenizer(vocab, merges);
        assertEquals(2, tokenizer.encode("ab")[0]);
        assertEquals(1, tokenizer.encode("ab").length);
    }
}
