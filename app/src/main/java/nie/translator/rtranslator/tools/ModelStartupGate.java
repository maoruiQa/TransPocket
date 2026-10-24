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
 * One startup load shared by every LoadingActivity instance.
 * A screen created after the user leaves joins this load instead of starting another one.
 * {@code runId} makes a callback from the discarded attempt ignore the attempt that replaced it.
 */
public final class ModelStartupGate {
    public interface Observer {
        void onReady();

        void onFailed(int[] reasons, long value);
    }

    public static final class Join {
        public final boolean start;
        public final int runId;

        public Join(boolean start, int runId) {
            this.start = start;
            this.runId = runId;
        }
    }

    private static final int IDLE = 0;
    private static final int RUNNING = 1;
    private static final int READY = 2;
    private static final int FAILED = 3;

    private final ArrayList<Observer> observers = new ArrayList<>();
    private int state = IDLE;
    private int runId = 0;
    private int[] failureReasons;
    private long failureValue;

    public synchronized Join beginOrJoin(Observer observer) {
        if (state == READY || state == FAILED) {
            return new Join(false, runId);
        }
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer);
        }
        if (state == RUNNING) {
            return new Join(false, runId);
        }
        state = RUNNING;
        return new Join(true, runId);
    }

    public void completeSuccess(int runId) {
        Observer[] copy = takeFinished(runId, READY, null, 0);
        if (copy == null) {
            return;
        }
        for (Observer observer : copy) {
            observer.onReady();
        }
    }

    public void completeFailure(int runId, int[] reasons, long value) {
        int[] copyReasons = reasons == null ? new int[0] : reasons.clone();
        Observer[] copy = takeFinished(runId, FAILED, copyReasons, value);
        if (copy == null) {
            return;
        }
        for (Observer observer : copy) {
            observer.onFailed(copyReasons, value);
        }
    }

    private synchronized Observer[] takeFinished(int runId, int nextState, int[] reasons, long value) {
        if (state != RUNNING || runId != this.runId) {
            return null;
        }
        state = nextState;
        failureReasons = reasons;
        failureValue = value;
        Observer[] copy = observers.toArray(new Observer[0]);
        observers.clear();
        return copy;
    }

    /** The failed attempt is over. Callbacks that still hold its run id must not complete the next one. */
    public synchronized void clearFailure() {
        if (state != FAILED) {
            return;
        }
        state = IDLE;
        failureReasons = null;
        runId++;
    }

    public synchronized void remove(Observer observer) {
        observers.remove(observer);
    }

    public synchronized boolean isReady() {
        return state == READY;
    }

    public synchronized boolean isRunning() {
        return state == RUNNING;
    }

    public synchronized boolean hasFailure() {
        return state == FAILED;
    }

    public synchronized int[] failureReasons() {
        return failureReasons == null ? null : failureReasons.clone();
    }

    public synchronized long failureValue() {
        return failureValue;
    }

    public synchronized int currentRunId() {
        return runId;
    }
}
