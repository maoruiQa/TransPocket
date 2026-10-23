package nie.translator.rtranslator.voice_translation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayDeque;

import javax.annotation.Nullable;

import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.TTS;
import nie.translator.rtranslator.tools.gui.messages.GuiMessage;

public class TTSEngine {
    public final static int DELAY_AFTER_TTS_INTERRUPTION_MS = 1500;
    private final ArrayDeque<Pair<GuiMessage, CustomLocale>> messageQueue = new ArrayDeque<>();
    private final ArrayDeque<StreamPiece> streamQueue = new ArrayDeque<>();
    private boolean streaming;
    private long streamId = -1;
    @Nullable
    private Pair<GuiMessage, CustomLocale> executingMessage = null;
    private boolean speaking = false;
    @Nullable
    private final TTS tts;
    private boolean pause = false;
    private Context context;
    @Nullable
    private TTSEngineListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());


    public TTSEngine(Context context, @Nullable TTSEngineListener listener) {
        this.context = context;
        this.tts = new TTS(context, new TTS.InitListener() {  // tts initialization (to be improved, automatic package installation)
            @Override
            public void onInit() {
                if(TTSEngine.this.tts != null) {
                    TTSEngine.this.tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onDone(String utteranceId) {
                            streaming = false;
                            streamId = -1;
                            if (drainStreamQueue()) {
                                return;
                            }
                            if (listener != null) {
                                boolean isLast = messageQueue.isEmpty();
                                listener.onUtteranceFinished(getExecutingMessage(), isLast);
                                executingMessage = null;
                            }
                            speak();
                        }

                        @Override
                        public void onError(String utteranceId) {
                            stop();
                        }

                        @Override
                        public void onStart(String utteranceId) {

                        }

                        @Override
                        public void onStop(String utteranceId, boolean interrupted) {
                            super.onStop(utteranceId, interrupted);

                            /*
                             * Usually, this interruption is triggered by TalkBack TTS.
                             * In this case, we put our stopped message back in the TTS queue, to resume the normal
                             * execution after TalkBack is done speaking.
                             * The insertion back in the TTS queue, is done after a delay, because often, TalkBack says multiple things,
                             * and inserts each phrase in the TTS after it has been spoken (not all at once).
                             * If we don't do the delay, we risk to put the TTS phrase in the middle of the TTS queue,
                             * giving a buggy feeling to the user, which will hear TalkBack, then out phrase, then TalkBack again with the second part.
                             */

                            if (pause) return; // Ignore if we stopped it manually via the pause() button

                            if (interrupted) {
                                Log.i("tts_engine", "TTS interrupted by TalkBack/System");

                                // Unstick the queue
                                speaking = false;

                                // Put the interrupted message back at the VERY FRONT of our custom queue
                                if (executingMessage != null) {
                                    messageQueue.addFirst(executingMessage);
                                    executingMessage = null;
                                }

                                // Trigger speak() again after a delay (so TalkBack can queue all its hints).
                                // Because TalkBack is currently speaking, QUEUE_ADD will gracefully
                                // put this message in the TTS engine's queue directly behind TalkBack.
                                handler.removeCallbacksAndMessages(null);  // Clear any pending resumes to avoid duplicates
                                handler.postDelayed(() -> {
                                    tts.stop();
                                    speak();
                                }, DELAY_AFTER_TTS_INTERRUPTION_MS);
                            } else {
                                stop();
                            }
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
        if (pause || message == null || tts == null) {
            return false;
        }
        String text = message.getMessage() == null || message.getMessage().getText() == null ? "" : message.getMessage().getText();
        if (canStream(language)) {
            return streamPiece(message, language, text, finished);
        }
        if (text.trim().isEmpty()) {
            return false;
        }
        return speak(message, language);
    }

    public boolean speak(GuiMessage message, CustomLocale language){
        messageQueue.addLast(new Pair<>(message, language));
        if (!messageQueue.isEmpty() && !speaking && !pause && !streaming) {
            return speak();
        }
        return false;
    }

    private boolean streamPiece(GuiMessage message, CustomLocale language, String text, boolean finished) {
        long id = message.getMessageID();
        if (streaming && streamId == id) {
            if (text.length() > 0) {
                tts.appendStream(text);
            }
            if (finished) {
                tts.finishStream();
            }
            return true;
        }
        if (text.trim().isEmpty()) {
            return false;
        }
        if (streaming || speaking) {
            streamQueue.addLast(new StreamPiece(message, language, text, finished));
            return true;
        }
        return openStream(message, language, text, finished);
    }

    private boolean openStream(GuiMessage message, CustomLocale language, String text, boolean finished) {
        boolean wasSpeaking = speaking;
        if (!tts.startStream(language, String.valueOf(message.getMessageID()))) {
            if (text.trim().isEmpty()) {
                return false;
            }
            return speak(message, language);
        }
        streaming = true;
        streamId = message.getMessageID();
        speaking = true;
        executingMessage = new Pair<>(message, language);
        if (listener != null) {
            listener.onUtteranceStarting(message, !wasSpeaking);
        }
        if (text.length() > 0) {
            tts.appendStream(text);
        }
        if (finished) {
            tts.finishStream();
        }
        return true;
    }

    private boolean drainStreamQueue() {
        if (pause || streamQueue.isEmpty() || tts == null) {
            return false;
        }
        StreamPiece first = streamQueue.pollFirst();
        if (!openStream(first.message, first.language, first.text, first.finished && !sameNext(first))) {
            return false;
        }
        while (!streamQueue.isEmpty() && streamQueue.peekFirst().message.getMessageID() == streamId) {
            StreamPiece next = streamQueue.pollFirst();
            if (next.text.length() > 0) {
                tts.appendStream(next.text);
            }
            if (next.finished) {
                tts.finishStream();
                break;
            }
        }
        return true;
    }

    private boolean sameNext(StreamPiece piece) {
        StreamPiece next = streamQueue.peekFirst();
        return next != null && next.message.getMessageID() == piece.message.getMessageID();
    }

    private boolean speak(){
        return speak(TextToSpeech.QUEUE_ADD);
    }

    private boolean speak(int queueMode){
        if(!pause) {
            boolean wasSpeaking = speaking;
            speaking = true;
            Pair<GuiMessage, CustomLocale> pair = messageQueue.pollFirst();
            if (pair != null && tts != null) {
                if(listener != null) listener.onUtteranceStarting(pair.first, !wasSpeaking);
                executingMessage = pair;
                tts.setLanguage(pair.second, context);
                int result = tts.speak(pair.first.getMessage().getText(), queueMode, null, String.valueOf(pair.first.getMessageID()));
                if (result == TextToSpeech.ERROR) {
                    stop();
                    return false;
                }
                return true;
            }else{
                speaking = false;
                executingMessage = null;
            }
        }
        return false;
    }

    public void pause() {
        if(!pause) {
            Log.i("tts_engine", "tts engine paused");
            handler.removeCallbacksAndMessages(null);
            if(tts != null) tts.stop();
            executingMessage = null;
            streaming = false;
            streamId = -1;
            pause = true;
        }
    }

    public void resume() {
        if(pause){
            Log.i("tts_engine", "tts engine resumed");
            handler.removeCallbacksAndMessages(null);
            pause = false;
            if (!drainStreamQueue()) {
                speak();
            }
        }
    }

    public boolean isPaused(){
        return pause;
    }

    public boolean isEmpty(){
        return messageQueue.isEmpty();
    }

    public void stop(){
        if(tts != null) tts.stop();
        boolean notify = listener != null && (executingMessage != null || !messageQueue.isEmpty());
        GuiMessage oldExecutingMessage = getExecutingMessage();
        executingMessage = null;
        streaming = false;
        streamId = -1;
        streamQueue.clear();
        messageQueue.clear();
        if(notify){
            listener.onUtteranceFinished(oldExecutingMessage, true);
        }
        speaking = false;
    }

    @Nullable
    public GuiMessage getExecutingMessage() {
        if(executingMessage != null) {
            return executingMessage.first;
        }
        return null;
    }

    @Nullable
    public TTS getTts() {
        return tts;
    }

    public void shutdown() {
        if(tts != null) tts.shutdown();
    }

    public boolean isActive() {
        if(tts != null) return tts.isActive();
        return false;
    }

    private static final class StreamPiece {
        private final GuiMessage message;
        private final CustomLocale language;
        private final String text;
        private final boolean finished;

        private StreamPiece(GuiMessage message, CustomLocale language, String text, boolean finished) {
            this.message = message;
            this.language = language;
            this.text = text;
            this.finished = finished;
        }
    }

    public static abstract class TTSEngineListener {
        abstract void onUtteranceStarting(GuiMessage message, boolean first);
        abstract void onUtteranceFinished(@Nullable GuiMessage message, boolean last);
        abstract void onInitError(int reason);
    }
}
