/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.tools;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Speech that still has to be played, in arrival order. One entry is submitted at a
 * time and the next one starts only after that entry reports back, so a new sentence
 * never cuts the one that is playing. Kept free of Android types so host unit tests
 * can cover the stall and interruption cases.
 */
public final class SpeechQueue<T> {
    /** The system engine plays the entry on its own, one sentence per request. */
    public static final int MODE_SPEAK = 0;
    /** A Live Draft session reads the entry while more text is appended to it. */
    public static final int MODE_STREAM = 1;

    public static final int RESULT_STALE = 0;
    public static final int RESULT_FINISHED = 1;
    public static final int RESULT_INTERRUPTED = 2;

    private final ArrayDeque<Entry<T>> waiting = new ArrayDeque<>();
    private Entry<T> inFlight;
    private boolean paused;

    public synchronized Entry<T> offer(T payload, long groupId, String text, int mode, boolean groupEnd, String utteranceId) {
        Entry<T> entry = new Entry<>(payload, groupId, text, mode, groupEnd, utteranceId);
        waiting.addLast(entry);
        return entry;
    }

    /**
     * Takes the next entry and marks it in flight, or returns null while paused, while
     * something is still playing, or while the queue is empty.
     */
    public synchronized Entry<T> beginSubmit() {
        if (paused || inFlight != null || waiting.isEmpty()) {
            return null;
        }
        inFlight = waiting.pollFirst();
        inFlight.started = false;
        return inFlight;
    }

    /**
     * The entry that {@link #beginSubmit()} would take, without taking it. Used to apply
     * the language and the settle delay before the request is submitted.
     */
    public synchronized Entry<T> peek() {
        if (paused) {
            return null;
        }
        return waiting.peekFirst();
    }

    public synchronized Entry<T> current() {
        return inFlight;
    }

    /**
     * The newest entry still waiting that belongs to {@code groupId}. Used to keep all
     * the pieces of one Live Draft utterance in a single entry, so its session is
     * opened once and closed when the text is finished.
     */
    public synchronized Entry<T> pendingFor(long groupId) {
        Iterator<Entry<T>> iterator = waiting.descendingIterator();
        while (iterator.hasNext()) {
            Entry<T> entry = iterator.next();
            if (entry.groupId() == groupId) {
                return entry;
            }
        }
        return null;
    }

    public synchronized boolean markStarted(String utteranceId) {
        if (inFlight == null || !inFlight.matches(utteranceId)) {
            return false;
        }
        inFlight.started = true;
        return true;
    }

    /** The in flight entry reported a normal end. Stale ids leave the state untouched. */
    public synchronized int onFinished(String utteranceId) {
        if (inFlight == null || !inFlight.matches(utteranceId)) {
            return RESULT_STALE;
        }
        inFlight = null;
        return RESULT_FINISHED;
    }

    /**
     * The in flight entry was interrupted by the engine. It goes back to the front so
     * the rest of the queue is not lost and the sentence is read again.
     */
    public synchronized int onInterrupted(String utteranceId) {
        if (inFlight == null || !inFlight.matches(utteranceId)) {
            return RESULT_STALE;
        }
        waiting.addFirst(inFlight);
        inFlight = null;
        return RESULT_INTERRUPTED;
    }

    /** The in flight entry failed. It is dropped and the queue moves on. */
    public synchronized int onFailed(String utteranceId) {
        if (inFlight == null || !inFlight.matches(utteranceId)) {
            return RESULT_STALE;
        }
        inFlight = null;
        return RESULT_FINISHED;
    }

    public synchronized boolean isInFlight(String utteranceId) {
        return inFlight != null && inFlight.matches(utteranceId);
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized void pause() {
        paused = true;
    }

    public synchronized void resume() {
        paused = false;
    }

    public synchronized boolean isEmpty() {
        return waiting.isEmpty() && inFlight == null;
    }

    public synchronized void clear() {
        waiting.clear();
        inFlight = null;
    }

    public static final class Entry<T> {
        private final T payload;
        private final long groupId;
        private int mode;
        private final String utteranceId;
        private String text;
        private boolean groupEnd;
        private boolean started;
        private boolean retried;

        private Entry(T payload, long groupId, String text, int mode, boolean groupEnd, String utteranceId) {
            this.payload = payload;
            this.groupId = groupId;
            this.text = text == null ? "" : text;
            this.mode = mode;
            this.groupEnd = groupEnd;
            this.utteranceId = utteranceId;
        }

        public T payload() {
            return payload;
        }

        public long groupId() {
            return groupId;
        }

        public String text() {
            return text;
        }

        public void appendText(String more) {
            if (more != null) {
                this.text = this.text + more;
            }
        }

        public int mode() {
            return mode;
        }

        /** The Live Draft session could not be opened, so the system engine reads this entry instead. */
        public void fallbackToSpeak() {
            this.mode = MODE_SPEAK;
        }

        public boolean groupEnd() {
            return groupEnd;
        }

        public void setGroupEnd(boolean groupEnd) {
            this.groupEnd = groupEnd;
        }

        public String utteranceId() {
            return utteranceId;
        }

        /** True once this entry was already replayed after an interruption. */
        public boolean retried() {
            return retried;
        }

        public void markRetried() {
            this.retried = true;
        }

        private boolean matches(String id) {
            return id != null && id.equals(utteranceId);
        }
    }
}
