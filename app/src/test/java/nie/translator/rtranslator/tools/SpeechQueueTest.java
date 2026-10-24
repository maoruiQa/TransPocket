package nie.translator.rtranslator.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SpeechQueueTest {
    @Test
    public void oneEntryIsSubmittedAtATime() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        queue.offer("first", 1, "First sentence.", SpeechQueue.MODE_SPEAK, true, "u1");
        queue.offer("second", 2, "Second sentence.", SpeechQueue.MODE_SPEAK, true, "u2");

        SpeechQueue.Entry<String> first = queue.beginSubmit();
        assertNotNull(first);
        assertEquals("u1", first.utteranceId());

        // The second sentence waits: it must not replace the one that is playing.
        assertNull(queue.beginSubmit());
        assertEquals("u1", queue.current().utteranceId());

        assertEquals(SpeechQueue.RESULT_FINISHED, queue.onFinished("u1"));
        SpeechQueue.Entry<String> second = queue.beginSubmit();
        assertNotNull(second);
        assertEquals("u2", second.utteranceId());
        assertFalse(queue.isEmpty());

        assertEquals(SpeechQueue.RESULT_FINISHED, queue.onFinished("u2"));
        assertTrue(queue.isEmpty());
        assertNull(queue.current());
    }

    @Test
    public void completionOfAnotherIdDoesNotWedgeTheQueue() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        queue.offer("first", 1, "First sentence.", SpeechQueue.MODE_SPEAK, true, "u1");
        queue.offer("second", 2, "Second sentence.", SpeechQueue.MODE_SPEAK, true, "u2");
        queue.beginSubmit();

        assertEquals(SpeechQueue.RESULT_STALE, queue.onFinished("u2"));
        assertEquals("u1", queue.current().utteranceId());

        assertEquals(SpeechQueue.RESULT_FINISHED, queue.onFinished("u1"));
        assertEquals("u2", queue.beginSubmit().utteranceId());
    }

    @Test
    public void interruptionPutsTheSentenceBackInFront() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        queue.offer("first", 1, "First sentence.", SpeechQueue.MODE_SPEAK, true, "u1");
        queue.offer("second", 2, "Second sentence.", SpeechQueue.MODE_SPEAK, true, "u2");
        queue.beginSubmit();

        assertEquals(SpeechQueue.RESULT_INTERRUPTED, queue.onInterrupted("u1"));
        assertNull(queue.current());

        SpeechQueue.Entry<String> replay = queue.beginSubmit();
        assertEquals("u1", replay.utteranceId());
        assertEquals(SpeechQueue.RESULT_FINISHED, queue.onFinished("u1"));
        assertEquals("u2", queue.beginSubmit().utteranceId());
    }

    @Test
    public void aFailureOnlyDropsTheFailedEntry() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        queue.offer("first", 1, "First sentence.", SpeechQueue.MODE_SPEAK, true, "u1");
        queue.offer("second", 2, "Second sentence.", SpeechQueue.MODE_SPEAK, true, "u2");
        queue.beginSubmit();

        assertEquals(SpeechQueue.RESULT_FINISHED, queue.onFailed("u1"));
        assertFalse(queue.isEmpty());
        assertEquals("u2", queue.beginSubmit().utteranceId());
    }

    @Test
    public void markStartedOnlyAcceptsTheEntryInFlight() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        queue.offer("first", 1, "First sentence.", SpeechQueue.MODE_SPEAK, true, "u1");
        queue.beginSubmit();

        assertFalse(queue.markStarted("u2"));
        assertTrue(queue.markStarted("u1"));
        assertTrue(queue.isInFlight("u1"));
        assertFalse(queue.isInFlight("u2"));
    }

    @Test
    public void pauseHoldsTheQueueUntilResume() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        queue.pause();
        queue.offer("first", 1, "First sentence.", SpeechQueue.MODE_SPEAK, true, "u1");
        assertNull(queue.beginSubmit());

        queue.resume();
        assertNotNull(queue.beginSubmit());
    }

    @Test
    public void clearDropsEverythingAndLateEventsAreIgnored() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        queue.offer("first", 1, "First sentence.", SpeechQueue.MODE_SPEAK, true, "u1");
        queue.beginSubmit();

        queue.clear();
        assertTrue(queue.isEmpty());
        assertEquals(SpeechQueue.RESULT_STALE, queue.onFinished("u1"));
        assertEquals(SpeechQueue.RESULT_STALE, queue.onInterrupted("u1"));

        queue.offer("next", 2, "Next sentence.", SpeechQueue.MODE_SPEAK, true, "u2");
        assertEquals("u2", queue.beginSubmit().utteranceId());
    }

    @Test
    public void piecesOfOneGroupAreMergedWhileWaiting() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        assertNull(queue.pendingFor(7L));

        queue.offer("message", 7L, "Hello,", SpeechQueue.MODE_STREAM, false, "u1");
        SpeechQueue.Entry<String> waiting = queue.pendingFor(7L);
        assertNotNull(waiting);
        waiting.appendText(" world.");
        waiting.setGroupEnd(true);

        assertEquals("Hello, world.", waiting.text());
        assertTrue(waiting.groupEnd());
        assertTrue(queue.pendingFor(7L) == waiting);
        assertNull(queue.pendingFor(8L));
    }

    @Test
    public void aReplayIsMarkedOnce() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        queue.offer("first", 1, "First sentence.", SpeechQueue.MODE_SPEAK, true, "u1");
        SpeechQueue.Entry<String> entry = queue.beginSubmit();

        assertFalse(entry.retried());
        entry.markRetried();
        assertTrue(entry.retried());
    }

    @Test
    public void peekShowsTheNextEntryWithoutTakingIt() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        assertNull(queue.peek());
        queue.offer("first", 1, "First sentence.", SpeechQueue.MODE_SPEAK, true, "u1");

        assertEquals("u1", queue.peek().utteranceId());
        assertNull(queue.current());
        assertEquals("u1", queue.peek().utteranceId());

        queue.beginSubmit();
        // While an entry is in flight, peek still reports the next one: TTSEngine checks
        // current() before it reads the language from peek().
        assertNull(queue.peek());
        assertEquals("u1", queue.current().utteranceId());
    }

    @Test
    public void aStreamEntryCanFallBackToTheSystemEngine() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        queue.offer("message", 1, "Text.", SpeechQueue.MODE_STREAM, true, "u1");
        SpeechQueue.Entry<String> entry = queue.beginSubmit();

        assertEquals(SpeechQueue.MODE_STREAM, entry.mode());
        entry.fallbackToSpeak();
        assertEquals(SpeechQueue.MODE_SPEAK, entry.mode());
    }
}
