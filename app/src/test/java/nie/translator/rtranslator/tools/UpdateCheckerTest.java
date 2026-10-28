package nie.translator.rtranslator.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UpdateCheckerTest {
    @Test
    public void extractsVersionFromReleaseNamesAndAssets() {
        assertEquals("0.1.1", UpdateChecker.extractVersion("TransPocket 0.1.1"));
        assertEquals("0.2.0", UpdateChecker.extractVersion("v0.2.0"));
        assertEquals("0.1.1", UpdateChecker.extractVersion("TransPocket-0.1.1.apk"));
        // GitHub gives a release without a tag a placeholder like this one.
        assertNull(UpdateChecker.extractVersion("untagged-7994ed1ebbb85211bd0a"));
        assertNull(UpdateChecker.extractVersion(null));
    }

    @Test
    public void comparesVersionsNumerically() {
        assertTrue(UpdateChecker.isNewer("0.1.10", "0.1.9"));
        assertTrue(UpdateChecker.isNewer("0.2", "0.1.9"));
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"));
        assertFalse(UpdateChecker.isNewer("0.1.1", "0.1.1"));
        assertFalse(UpdateChecker.isNewer("0.1.1", "0.1.1.0"));
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.1"));
    }

    @Test
    public void unknownVersionsAreNotOffered() {
        assertFalse(UpdateChecker.isNewer("untagged", "0.1.1"));
        assertTrue(UpdateChecker.isNewer("0.1.2", "debug"));
    }
}
