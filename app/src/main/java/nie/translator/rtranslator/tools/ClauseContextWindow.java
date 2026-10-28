package nie.translator.rtranslator.tools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The recent clauses that simultaneous interpretation may pass as context.
 * A misheard clause is local noise: it is kept out, but the trusted clauses before
 * it stay, because the talk usually goes on with the same subject.
 * Kept free of Android types for host unit tests.
 */
public final class ClauseContextWindow {
    public static final int MAX_CLAUSES = 3;
    /** Bounds the prompt, and so the latency, of every following clause. */
    public static final int MAX_CHARS = 200;
    /** After a longer pause the talk has probably moved on. */
    public static final long MAX_GAP_MS = 15000;

    public static final class Entry {
        public final String source;
        public final String translation;
        public final int level;
        public final long endedAt;

        Entry(String source, String translation, int level, long endedAt) {
            this.source = source;
            this.translation = translation;
            this.level = level;
            this.endedAt = endedAt;
        }

        /** MEDIUM: probably one misheard word. Only prompts that can be told so get it. */
        public boolean isDoubtful() {
            return level == ClauseConfidence.MEDIUM;
        }
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    /** LOW clauses are dropped. UNKNOWN (grading off) counts as trusted. */
    public void add(String source, String translation, int level, long now) {
        expire(now);
        if (level == ClauseConfidence.LOW || source == null || source.trim().isEmpty()) {
            return;
        }
        entries.addLast(new Entry(source.trim(), translation == null ? "" : translation.trim(), level, now));
        while (entries.size() > MAX_CLAUSES) {
            entries.removeFirst();
        }
    }

    public void clear() {
        entries.clear();
    }

    /** Trusted and doubtful clauses, oldest first, within the character budget. */
    public List<Entry> lines(long now) {
        return select(now, true);
    }

    /** Trusted clauses only, oldest first, within the character budget. */
    public List<Entry> trusted(long now) {
        return select(now, false);
    }

    /** The newest trusted clause, for engines that only take one sentence of context. */
    public Entry lastTrusted(long now) {
        List<Entry> trusted = trusted(now);
        return trusted.isEmpty() ? null : trusted.get(trusted.size() - 1);
    }

    private List<Entry> select(long now, boolean withDoubtful) {
        expire(now);
        ArrayList<Entry> selected = new ArrayList<>();
        int chars = 0;
        Iterator<Entry> newestFirst = entries.descendingIterator();
        while (newestFirst.hasNext()) {
            Entry entry = newestFirst.next();
            if (entry.isDoubtful() && !withDoubtful) {
                continue;
            }
            chars += entry.source.length();
            if (!selected.isEmpty() && chars > MAX_CHARS) {
                break;
            }
            selected.add(0, entry);
        }
        return selected;
    }

    /** Measured from the newest clause, so a run of dropped clauses also ages the context out. */
    private void expire(long now) {
        Entry newest = entries.peekLast();
        if (newest != null && now - newest.endedAt > MAX_GAP_MS) {
            entries.clear();
        }
    }
}
