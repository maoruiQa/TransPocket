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
    /**
     * {@link #history} only grows until it passes one of these, then drops all but the newest
     * {@link #KEEP_HISTORY_CLAUSES} at once. A window that slid by one clause would change the
     * start of every GGUF prompt, and the model would decode all of it again for each clause.
     */
    public static final int MAX_HISTORY_CLAUSES = 8;
    /** Source and translation characters together; about 500 prompt tokens. */
    public static final int MAX_HISTORY_CHARS = 1000;
    public static final int KEEP_HISTORY_CLAUSES = 2;

    public static final class Entry {
        public final String source;
        public final String translation;
        /** The GGUF model's raw answer, rating included, or null for other engines. */
        public final String answer;
        public final int level;
        public final long endedAt;

        Entry(String source, String translation, String answer, int level, long endedAt) {
            this.source = source;
            this.translation = translation;
            this.answer = answer;
            this.level = level;
            this.endedAt = endedAt;
        }

        /** MEDIUM: probably one misheard word. Only prompts that can be told so get it. */
        public boolean isDoubtful() {
            return level == ClauseConfidence.MEDIUM;
        }
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    /** The oldest entry of {@link #history}, or null when it is empty. */
    private Entry historyStart;

    public void add(String source, String translation, int level, long now) {
        add(source, translation, null, level, now);
    }

    /** LOW clauses are dropped. UNKNOWN (grading off) counts as trusted. */
    public void add(String source, String translation, String answer, int level, long now) {
        expire(now);
        if (level == ClauseConfidence.LOW || source == null || source.trim().isEmpty()) {
            return;
        }
        Entry entry = new Entry(source.trim(), translation == null ? "" : translation.trim(), answer, level, now);
        entries.addLast(entry);
        if (historyStart == null) {
            historyStart = entry;
        }
        List<Entry> history = history();
        int chars = 0;
        for (Entry item : history) {
            chars += item.source.length() + item.translation.length();
        }
        if (history.size() > MAX_HISTORY_CLAUSES || chars > MAX_HISTORY_CHARS) {
            historyStart = history.get(Math.max(0, history.size() - KEEP_HISTORY_CLAUSES));
        }
        // Entries older than the history are kept only as far as lines() and trusted() reach.
        while (entries.size() > MAX_CLAUSES && entries.peekFirst() != historyStart) {
            entries.removeFirst();
        }
    }

    public void clear() {
        entries.clear();
        historyStart = null;
    }

    /**
     * Trusted and doubtful clauses, oldest first, for prompts that the GGUF runtime keeps decoded.
     * Between cut-backs each call returns the previous list plus the newest clause.
     */
    public List<Entry> history(long now) {
        expire(now);
        return history();
    }

    private List<Entry> history() {
        ArrayList<Entry> history = new ArrayList<>();
        boolean started = false;
        for (Entry entry : entries) {
            started |= entry == historyStart;
            if (started) {
                history.add(entry);
            }
        }
        return history;
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
        // Only the newest MAX_CLAUSES entries; the older ones are kept for history().
        int seen = 0;
        while (newestFirst.hasNext() && seen++ < MAX_CLAUSES) {
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
            clear();
        }
    }
}
