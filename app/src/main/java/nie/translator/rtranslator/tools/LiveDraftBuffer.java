package nie.translator.rtranslator.tools;

/**
 * Holds text that is still growing and releases only a stable piece.
 * A word that has not reached a boundary stays in the buffer.
 */
public final class LiveDraftBuffer {
    private final StringBuilder pending = new StringBuilder();

    public void add(String text) {
        if (text != null && text.length() > 0) {
            pending.append(text);
        }
    }

    /** The next piece that can be synthesized, or null while the rest should wait. */
    public String pull(boolean finished) {
        String piece = SimultaneousText.takeSpeakable(pending.toString(), finished);
        if (piece == null) {
            return null;
        }
        pending.delete(0, piece.length());
        return piece;
    }

    public String pending() {
        return pending.toString();
    }
}
