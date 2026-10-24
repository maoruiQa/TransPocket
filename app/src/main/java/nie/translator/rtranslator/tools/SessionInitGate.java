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

package nie.translator.rtranslator.tools;

import java.util.ArrayList;

/**
 * Translator and speech-recognizer init.
 * The object exists before its sessions do, so a second caller joins the open attempt
 * instead of treating the object as ready. {@link #reset()} drops that attempt.
 */
public final class SessionInitGate {
    public interface Listener {
        void onSuccess();

        void onFailure(int[] reasons, long value);
    }

    public static final class Join {
        public final boolean start;
        public final int generation;

        public Join(boolean start, int generation) {
            this.start = start;
            this.generation = generation;
        }
    }

    private static final int IDLE = 0;
    private static final int RUNNING = 1;
    private static final int READY = 2;

    private final ArrayList<Listener> listeners = new ArrayList<>();
    private int state = IDLE;
    private int generation = 0;

    public Join join(Listener listener) {
        Listener immediate = null;
        boolean start;
        int generation;
        synchronized (this) {
            generation = this.generation;
            if (state == READY) {
                immediate = listener;
                start = false;
            } else if (state == RUNNING) {
                if (listener != null) {
                    listeners.add(listener);
                }
                start = false;
            } else {
                state = RUNNING;
                if (listener != null) {
                    listeners.add(listener);
                }
                start = true;
            }
        }
        if (immediate != null) {
            immediate.onSuccess();
        }
        return new Join(start, generation);
    }

    public void succeed(int generation) {
        Listener[] copy = finish(generation, READY);
        if (copy == null) {
            return;
        }
        for (Listener listener : copy) {
            listener.onSuccess();
        }
    }

    public void fail(int generation, int[] reasons, long value) {
        int[] copyReasons = reasons == null ? new int[0] : reasons.clone();
        Listener[] copy = finish(generation, IDLE);
        if (copy == null) {
            return;
        }
        for (Listener listener : copy) {
            listener.onFailure(copyReasons, value);
        }
    }

    private synchronized Listener[] finish(int generation, int nextState) {
        if (this.generation != generation || state != RUNNING) {
            return null;
        }
        state = nextState;
        Listener[] copy = listeners.toArray(new Listener[0]);
        listeners.clear();
        return copy;
    }

    /** Drops waiters and ignores a callback from the session that was just discarded. */
    public synchronized void reset() {
        generation++;
        state = IDLE;
        listeners.clear();
    }

    public synchronized boolean isReady() {
        return state == READY;
    }

    public synchronized boolean isRunning() {
        return state == RUNNING;
    }
}
