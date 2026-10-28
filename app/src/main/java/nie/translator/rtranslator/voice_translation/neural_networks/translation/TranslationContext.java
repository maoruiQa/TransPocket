package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nie.translator.rtranslator.tools.ClauseConfidence;

/**
 * What a voice clause is translated with: the earlier clauses in the form each engine
 * can use, the grade of its speech recognition, and whether the model rates itself.
 */
public class TranslationContext {
    public static class Line {
        public final String text;
        /** Probably one misheard word. The prompt says so instead of dropping it. */
        public final boolean doubtful;

        public Line(String text, boolean doubtful) {
            this.text = text;
            this.doubtful = doubtful;
        }
    }

    /** Newest trusted clause. Sentence-level engines get it prepended, then stripped from the output. */
    @Nullable
    public final String lastSource;
    @Nullable
    public final String lastTranslation;
    /** Trusted clauses in order, for the HY-MT context template. */
    @Nullable
    public final String joinedSource;
    /** Trusted and doubtful clauses in order, for the GGUF prompts. */
    public final List<Line> lines;
    /** One of the {@link ClauseConfidence} grades. */
    public final int asrLevel;
    /** Ask a model that can follow it to end its answer with a {@code [Q=n]} rating. */
    public final boolean selfRating;

    public TranslationContext(@Nullable String lastSource, @Nullable String lastTranslation, @Nullable String joinedSource, @Nullable List<Line> lines, int asrLevel, boolean selfRating) {
        this.lastSource = lastSource;
        this.lastTranslation = lastTranslation;
        this.joinedSource = joinedSource;
        this.lines = lines == null ? Collections.<Line>emptyList() : new ArrayList<>(lines);
        this.asrLevel = asrLevel;
        this.selfRating = selfRating;
    }

    /** No earlier clauses, as in WalkieTalkie. */
    public static TranslationContext withoutHistory(int asrLevel, boolean selfRating) {
        return new TranslationContext(null, null, null, null, asrLevel, selfRating);
    }
}
