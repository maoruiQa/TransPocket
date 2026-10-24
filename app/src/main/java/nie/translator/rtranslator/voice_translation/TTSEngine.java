package nie.translator.rtranslator.voice_translation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayList;

import javax.annotation.Nullable;

import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.SimultaneousText;
import nie.translator.rtranslator.tools.SpeechQueue;
import nie.translator.rtranslator.tools.TTS;
import nie.translator.rtranslator.tools.gui.messages.GuiMessage;

/**
 * Plays translations in arrival order. One utterance is submitted at a time and the
 * next one waits for it to end, so a new sentence never cuts the one that is playing
 * and the system engine is never asked to hold a queue of its own.
 */
public class TTSEngine {
    public final static int DELAY_AFTER_TTS_INTERRUPTION_MS = 1500;
    /** An accepted utterance that never reports onStart. */
    private static final long SUBMIT_TIMEOUT_MS = 5000;
    /** An utterance that reported onStart but never reports onDone. */
    private static final long MIN_SPEECH_BUDGET_MS = 10000;

    private final SpeechQueue<Pair<GuiMessage, CustomLocale>> queue = new SpeechQueue<>();
    private boolean speaking = false;
    private long nextUtteranceId = 0;
    /** Earliest time a new request may be submitted. See {@link TTS#ENGINE_SETTLE_MS}. */
    private long notBefore = 0;
    private long submittedAt = 0;
    private long lastStopAt = 0;
    private long lastDoneAt = 0;
    @Nullable
    private final TTS tts;
    private boolean pause = false;
    private Context context;
    @Nullable
    private TTSEngineListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable
    private Runnable watch;

    private final Runnable pumpRunnable = new Runnable() {
        @Override
        public void run() {
            pump();
        }
    };


    public TTSEngine(Context context, @Nullable TTSEngineListener listener) {
        this.context = context;
        this.tts = new TTS(context, new TTS.InitListener() {  // tts initialization (to be improved, automatic package installation)
            @Override
            public void onInit() {
                if(TTSEngine.this.tts != null) {
                    TTSEngine.this.tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    handleStarted(utteranceId);
                                }
                            });
                        }

                        @Override
                        public void onDone(String utteranceId) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    handleDone(utteranceId);
                                }
                            });
                        }

                        @Override
                        public void onError(String utteranceId) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    handleError(utteranceId);
                                }
                            });
                        }

                        /**
                         * Usually, this interruption is triggered by TalkBack TTS.
                         * In this case, the stopped sentence goes back to the front of our queue, to resume the normal
                         * execution after TalkBack is done speaking.
                         * The replay is delayed, because often TalkBack says multiple things, and inserts each phrase in
                         * the TTS after it has been spoken (not all at once). Without the delay we risk to put our phrase
                         * in the middle of the TalkBack ones, which sounds broken to the user.
                         */
                        @Override
                        public void onStop(String utteranceId, boolean interrupted) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    handleStopped(utteranceId, interrupted);
                                }
                            });
                        }
                    });
                }
            }

            @Override
            public void onError(int reason) {

            }
        });
        this.listener = listener;

    }


    public boolean canStream(CustomLocale language) {
        return tts != null && tts.canStream(language);
    }

    /**
     * Feeds one stable piece of an utterance. Live Draft keeps a single session
     * open for {@code message}'s id. The system engine queues each piece.
     */
    public boolean speakPiece(GuiMessage message, CustomLocale language, boolean finished) {
        if (message == null || tts == null) {
            return false;
        }
        String text = message.getMessage() == null || message.getMessage().getText() == null ? "" : message.getMessage().getText();
        SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> stream = openStreamFor(message.getMessageID());
        if (stream != null) {
            if (text.length() > 0) {
                tts.appendStream(text);
                stream.appendText(text);
            }
            if (finished) {
                stream.setGroupEnd(true);
                tts.finishStream();
            }
            return true;
        }
        if (!SimultaneousText.isAudible(text)) {
            if (finished) {
                // The final callback can carry no new audible text. The Live Draft session of
                // this message still has to be closed, even if it is only waiting in the queue.
                SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> waiting = queue.pendingFor(message.getMessageID());
                if (waiting != null && waiting.mode() == SpeechQueue.MODE_STREAM) {
                    waiting.setGroupEnd(true);
                }
            }
            return false;
        }
        return enqueue(message, language, text, finished);
    }

    public boolean speak(GuiMessage message, CustomLocale language){
        if (message == null || tts == null) {
            return false;
        }
        String text = message.getMessage() == null || message.getMessage().getText() == null ? "" : message.getMessage().getText();
        if (!SimultaneousText.isAudible(text)) {
            return false;
        }
        return enqueue(message, language, text, true);
    }

    private boolean enqueue(GuiMessage message, CustomLocale language, String text, boolean groupEnd) {
        if (tts == null) {
            return false;
        }
        Pair<GuiMessage, CustomLocale> payload = new Pair<>(message, language);
        long groupId = message.getMessageID();
        if (tts.isLiveDraft(language)) {
            // A Live Draft session is opened once per message: the pieces are kept in one
            // entry, because a session that waits for more text never reports it is done.
            SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> waiting = queue.pendingFor(groupId);
            if (waiting != null) {
                waiting.appendText(text);
                if (groupEnd) {
                    waiting.setGroupEnd(true);
                }
                return true;
            }
            queue.offer(payload, groupId, text, SpeechQueue.MODE_STREAM, groupEnd, nextUtteranceId());
            pump();
            return true;
        }
        ArrayList<String> parts = new ArrayList<>();
        for (String part : splitForEngine(text)) {
            // A piece without a letter or a digit makes some engines answer ERROR or stay silent.
            if (SimultaneousText.isAudible(part)) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            parts.add(text);
        }
        for (int i = 0; i < parts.size(); i++) {
            boolean last = groupEnd && i == parts.size() - 1;
            queue.offer(payload, groupId, parts.get(i), SpeechQueue.MODE_SPEAK, last, nextUtteranceId());
        }
        pump();
        return true;
    }

    /** The engine rejects very long single requests, so a whole message is sent in pieces. */
    private ArrayList<String> splitForEngine(String text) {
        ArrayList<String> parts = new ArrayList<>();
        int max = tts == null ? 0 : tts.getMaxSpeechInputLength();
        String rest = text == null ? "" : text;
        if (max <= 1) {
            parts.add(rest);
            return parts;
        }
        while (!rest.isEmpty()) {
            String piece = SimultaneousText.takeSpeakable(rest, true, max, true);
            if (piece == null || piece.isEmpty()) {
                break;
            }
            parts.add(piece);
            if (piece.length() >= rest.length()) {
                break;
            }
            rest = rest.substring(piece.length());
        }
        if (parts.isEmpty()) {
            parts.add(rest);
        }
        return parts;
    }

    private String nextUtteranceId() {
        nextUtteranceId++;
        return "u" + nextUtteranceId;
    }

    @Nullable
    private SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> openStreamFor(long messageId) {
        SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> current = queue.current();
        if (current != null && current.mode() == SpeechQueue.MODE_STREAM && current.groupId() == messageId) {
            return current;
        }
        return null;
    }

    /**
     * Submits the head of the queue. Runs on the main thread, because the engine
     * prefers to be driven from there. Never flushes: the previous utterance is
     * always over (or was stopped explicitly) before a new one is submitted.
     */
    private void pump() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(pumpRunnable);
            return;
        }
        if (pause || tts == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < notBefore) {
            // The engine is still tearing down the previous output (or just changed
            // language). A request submitted now loses its first words.
            handler.removeCallbacks(pumpRunnable);
            handler.postDelayed(pumpRunnable, notBefore - now);
            return;
        }
        if (queue.current() != null) {
            // Something is still playing: its terminal callback calls pump again.
            return;
        }
        SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> head = queue.peek();
        if (head == null) {
            if (queue.isEmpty()) {
                speaking = false;
            }
            return;
        }
        CustomLocale headLanguage = head.payload().second;
        boolean engineChanged = tts.prepareLanguage(headLanguage, context);
        if (tts.prepareSpeechRate()) {
            engineChanged = true;
        }
        if (engineChanged) {
            notBefore = now + TTS.ENGINE_SETTLE_MS;
            handler.removeCallbacks(pumpRunnable);
            handler.postDelayed(pumpRunnable, TTS.ENGINE_SETTLE_MS);
            return;
        }
        SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> entry = queue.beginSubmit();
        if (entry == null) {
            if (queue.isEmpty()) {
                speaking = false;
            }
            return;
        }
        Pair<GuiMessage, CustomLocale> pair = entry.payload();
        GuiMessage message = pair.first;
        CustomLocale language = pair.second;
        boolean first = !speaking;
        speaking = true;
        if (first) {
            tts.requestSpeechFocus();
        }
        if (listener != null) {
            listener.onUtteranceStarting(message, first);
        }
        if (entry.mode() == SpeechQueue.MODE_STREAM) {
            if (tts.startStream(language, entry.utteranceId())) {
                if (entry.text().length() > 0) {
                    tts.appendStream(entry.text());
                }
                if (entry.groupEnd()) {
                    tts.finishStream();
                }
                submittedAt = now;
                armWatch(entry, false);
                return;
            }
            // Live Draft could not open the session, so this entry goes through the system engine.
            entry.fallbackToSpeak();
        }
        Log.i("tts_engine", "submit " + entry.utteranceId() + " chars=" + entry.text().length()
                + " sinceDone=" + (lastDoneAt == 0 ? -1 : now - lastDoneAt)
                + " sinceStop=" + (lastStopAt == 0 ? -1 : now - lastStopAt)
                + " text=" + preview(entry.text()));
        submittedAt = now;
        int result = tts.speak(entry.text(), TextToSpeech.QUEUE_ADD, null, entry.utteranceId());
        if (result == TextToSpeech.ERROR) {
            Log.w("tts_engine", "the engine refused an utterance, dropping it");
            drop(entry);
            return;
        }
        armWatch(entry, false);
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ');
        return flat.length() <= 60 ? flat : flat.substring(0, 60);
    }

    private void handleStarted(String utteranceId) {
        SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> entry = queue.current();
        if (entry == null || !queue.markStarted(utteranceId)) {
            return;
        }
        Log.i("tts_engine", "started " + utteranceId + " after " + (SystemClock.elapsedRealtime() - submittedAt) + " ms");
        armWatch(entry, true);
    }

    private void handleDone(String utteranceId) {
        SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> entry = queue.current();
        if (entry == null || !queue.isInFlight(utteranceId)) {
            return;
        }
        disarmWatch();
        if (queue.onFinished(utteranceId) == SpeechQueue.RESULT_STALE) {
            return;
        }
        lastDoneAt = SystemClock.elapsedRealtime();
        Log.i("tts_engine", "done " + utteranceId + " after " + (lastDoneAt - submittedAt) + " ms");
        completed(entry);
    }

    private void handleError(String utteranceId) {
        SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> entry = queue.current();
        if (entry == null || !queue.isInFlight(utteranceId)) {
            return;
        }
        Log.w("tts_engine", "the engine reported an error on an utterance, dropping it");
        disarmWatch();
        queue.onFailed(utteranceId);
        dropped(entry);
    }

    /**
     * An interruption that we did not ask for (TalkBack or the engine itself). The
     * sentence goes back to the front of the queue and is read again after a delay.
     */
    private void handleStopped(String utteranceId, boolean interrupted) {
        SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> entry = queue.current();
        if (entry == null || !queue.isInFlight(utteranceId)) {
            return;
        }
        if (pause) {
            return; // we stopped it ourselves
        }
        if (!interrupted) {
            handleDone(utteranceId);
            return;
        }
        Log.i("tts_engine", "TTS interrupted by TalkBack/System");
        disarmWatch();
        if (entry.retried()) {
            // A second interruption of the same sentence: do not loop on it forever.
            queue.onFailed(utteranceId);
            dropped(entry);
            return;
        }
        entry.markRetried();
        queue.onInterrupted(utteranceId);
        speaking = false;
        handler.removeCallbacks(pumpRunnable);
        handler.postDelayed(pumpRunnable, DELAY_AFTER_TTS_INTERRUPTION_MS);
    }

    /**
     * The entry ended normally. The finished callback closes the message when its last
     * piece is over, and always when the queue drains: the microphone of the modes that
     * turn it off during playback must hear again even if a piece was lost.
     */
    private void completed(SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> entry) {
        // onDone arrives before the audio output is fully drained: give the engine the
        // settle time, otherwise the next sentence loses its first words. Set before the
        // listener runs, because a mode can hand over the next sentence from its callback.
        notBefore = SystemClock.elapsedRealtime() + TTS.ENGINE_SETTLE_MS;
        boolean last = queue.isEmpty();
        if (listener != null && (entry.groupEnd() || last)) {
            listener.onUtteranceFinished(entry.payload().first, last);
        }
        if (last) {
            speaking = false;
            if (tts != null) {
                tts.abandonSpeechFocus();
            }
            return;
        }
        pump();
    }

    /** The entry was dropped without being played. The queue still has to move on. */
    private void dropped(SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> entry) {
        boolean last = queue.isEmpty();
        if (listener != null && (entry.groupEnd() || last)) {
            listener.onUtteranceFinished(entry.payload().first, last);
        }
        if (last) {
            speaking = false;
            if (tts != null) {
                tts.abandonSpeechFocus();
            }
            return;
        }
        pump();
    }

    private void drop(SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> entry) {
        disarmWatch();
        queue.onFailed(entry.utteranceId());
        dropped(entry);
    }

    /**
     * The engine is the only thing that knows when a sentence is over, and some
     * engines never report it. This timeout only recovers the queue: it never
     * replays the text and it never asks the engine to flush the next sentence in.
     */
    private void armWatch(final SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> entry, boolean started) {
        disarmWatch();
        long budget;
        if (started) {
            budget = Math.max(MIN_SPEECH_BUDGET_MS, entry.text().length() * 400L + 8000L);
        } else {
            budget = SUBMIT_TIMEOUT_MS;
        }
        final String utteranceId = entry.utteranceId();
        watch = new Runnable() {
            @Override
            public void run() {
                onWatchExpired(utteranceId);
            }
        };
        handler.postDelayed(watch, budget);
    }

    private void disarmWatch() {
        if (watch != null) {
            handler.removeCallbacks(watch);
            watch = null;
        }
    }

    private void onWatchExpired(String utteranceId) {
        SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> entry = queue.current();
        if (entry == null || !queue.isInFlight(utteranceId)) {
            return;
        }
        Log.w("tts_engine", "the engine did not report the end of an utterance, recovering");
        disarmWatch();
        queue.onFailed(utteranceId);
        if (tts != null) {
            // The utterance may still be playing in the engine. Stopping it here keeps the
            // next sentence from being spoken over it.
            tts.stop();
            lastStopAt = SystemClock.elapsedRealtime();
            notBefore = lastStopAt + TTS.ENGINE_SETTLE_MS;
        }
        dropped(entry);
    }

    public void pause() {
        if(!pause) {
            Log.i("tts_engine", "tts engine paused");
            handler.removeCallbacks(pumpRunnable);
            disarmWatch();
            pause = true;
            queue.pause();
            // The sentence that was playing is put back, so it is read from the start after the user's turn.
            SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> current = queue.current();
            if (current != null) {
                queue.onInterrupted(current.utteranceId());
            }
            speaking = false;
            if(tts != null) {
                tts.stop();
                lastStopAt = SystemClock.elapsedRealtime();
                notBefore = lastStopAt + TTS.ENGINE_SETTLE_MS;
                tts.abandonSpeechFocus();
            }
        }
    }

    public void resume() {
        if(pause){
            Log.i("tts_engine", "tts engine resumed");
            handler.removeCallbacks(pumpRunnable);
            pause = false;
            queue.resume();
            pump();
        }
    }

    public boolean isPaused(){
        return pause;
    }

    public boolean isEmpty(){
        return queue.isEmpty();
    }

    public void stop(){
        handler.removeCallbacks(pumpRunnable);
        disarmWatch();
        boolean notify = listener != null && (speaking || !queue.isEmpty());
        GuiMessage oldExecutingMessage = getExecutingMessage();
        queue.clear();
        speaking = false;
        if(tts != null) {
            tts.stop();
            lastStopAt = SystemClock.elapsedRealtime();
            // A request submitted right after stop() is played while the engine is still
            // closing the previous output, and loses its first words.
            notBefore = lastStopAt + TTS.ENGINE_SETTLE_MS;
            tts.abandonSpeechFocus();
        }
        if(notify){
            listener.onUtteranceFinished(oldExecutingMessage, true);
        }
    }

    @Nullable
    public GuiMessage getExecutingMessage() {
        SpeechQueue.Entry<Pair<GuiMessage, CustomLocale>> current = queue.current();
        if (current != null) {
            return current.payload().first;
        }
        return null;
    }

    @Nullable
    public TTS getTts() {
        return tts;
    }

    public void shutdown() {
        handler.removeCallbacks(pumpRunnable);
        disarmWatch();
        queue.clear();
        if(tts != null) tts.shutdown();
    }

    public boolean isActive() {
        if(tts != null) return tts.isActive();
        return false;
    }

    public static abstract class TTSEngineListener {
        abstract void onUtteranceStarting(GuiMessage message, boolean first);
        abstract void onUtteranceFinished(@Nullable GuiMessage message, boolean last);
        abstract void onInitError(int reason);
    }
}
