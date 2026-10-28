package nie.translator.rtranslator.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ClauseConfidenceTest {
    @Test
    public void asrScoreThresholds() {
        assertEquals(ClauseConfidence.HIGH, ClauseConfidence.fromAsrScore(-0.2));
        assertEquals(ClauseConfidence.MEDIUM, ClauseConfidence.fromAsrScore(-0.6));
        assertEquals(ClauseConfidence.LOW, ClauseConfidence.fromAsrScore(-1.5));
    }

    @Test
    public void missingAsrScoreIsUnknown() {
        assertEquals(ClauseConfidence.UNKNOWN, ClauseConfidence.fromAsrScore(0));
        assertEquals(ClauseConfidence.UNKNOWN, ClauseConfidence.fromAsrScore(Double.NaN));
        assertEquals(ClauseConfidence.UNKNOWN, ClauseConfidence.fromAsrScore(Double.NEGATIVE_INFINITY));
    }

    @Test
    public void cleanTextWithoutScoreIsHigh() {
        assertEquals(ClauseConfidence.HIGH, ClauseConfidence.asrLevel("今天天气很好", 0, 1500));
    }

    @Test
    public void lowScoreIsLow() {
        assertEquals(ClauseConfidence.LOW, ClauseConfidence.asrLevel("今天天气很好", -1.2, 1500));
    }

    @Test
    public void hallucinatedPhraseIsLow() {
        assertEquals(ClauseConfidence.LOW, ClauseConfidence.asrLevel("Thank you for watching!", -0.1, 2000));
        assertEquals(ClauseConfidence.LOW, ClauseConfidence.asrLevel("谢谢观看", -0.1, 2000));
    }

    @Test
    public void tooFastSpeechIsLow() {
        assertEquals(ClauseConfidence.LOW, ClauseConfidence.asrLevel("这是一段非常非常长的根本不可能在半秒内说完的话", -0.1, 500));
    }

    @Test
    public void longAudioWithOneWordIsMedium() {
        assertEquals(ClauseConfidence.MEDIUM, ClauseConfidence.asrLevel("嗯", -0.1, 4000));
    }

    @Test
    public void repeatedPhraseIsLow() {
        assertEquals(ClauseConfidence.LOW, ClauseConfidence.asrLevel("I think so I think so I think so I think so", -0.1, 0));
    }

    @Test
    public void shortFragmentIsMedium() {
        assertEquals(ClauseConfidence.MEDIUM, ClauseConfidence.fragmentLevel("好的"));
        assertEquals(ClauseConfidence.MEDIUM, ClauseConfidence.fragmentLevel("Okay."));
        assertEquals(ClauseConfidence.HIGH, ClauseConfidence.fragmentLevel("We meet at noon"));
    }

    @Test
    public void untranslatedOutputIsMedium() {
        assertEquals(ClauseConfidence.MEDIUM, ClauseConfidence.translationLevel("Hello there", "Hello there", null, false));
    }

    @Test
    public void extremeLengthRatioIsMedium() {
        assertEquals(ClauseConfidence.MEDIUM, ClauseConfidence.translationLevel("We will meet at the station tomorrow", "好", null, false));
    }

    @Test
    public void normalTranslationIsHigh() {
        assertEquals(ClauseConfidence.HIGH, ClauseConfidence.translationLevel("We will meet at the station tomorrow", "我们明天在车站见面", null, false));
    }

    @Test
    public void contextEchoIsMedium() {
        String previous = "We will meet at the station tomorrow";
        assertEquals(ClauseConfidence.MEDIUM, ClauseConfidence.translationLevel("记得带票", "We will meet at the station, remember the ticket", previous, false));
    }

    @Test
    public void modelRatingIsParsedAndStripped() {
        assertEquals(ClauseConfidence.MEDIUM, ClauseConfidence.modelRating("We meet at noon.\n[Q=2]"));
        assertEquals("We meet at noon.", ClauseConfidence.withoutModelRating("We meet at noon.\n[Q=2]"));
        assertEquals(ClauseConfidence.LOW, ClauseConfidence.modelRating("text [ q = 1 ]"));
    }

    @Test
    public void missingModelRatingIsUnknown() {
        assertEquals(ClauseConfidence.UNKNOWN, ClauseConfidence.modelRating("We meet at noon."));
        assertEquals(ClauseConfidence.UNKNOWN, ClauseConfidence.modelRating("[Q=5]"));
    }

    @Test
    public void modelOnlyLowers() {
        assertEquals(ClauseConfidence.MEDIUM, ClauseConfidence.withModelRating(ClauseConfidence.MEDIUM, ClauseConfidence.HIGH));
        assertEquals(ClauseConfidence.LOW, ClauseConfidence.withModelRating(ClauseConfidence.MEDIUM, ClauseConfidence.LOW));
    }

    @Test
    public void modelCannotTakeCleanSignalsToLow() {
        assertEquals(ClauseConfidence.MEDIUM, ClauseConfidence.withModelRating(ClauseConfidence.HIGH, ClauseConfidence.LOW));
    }

    @Test
    public void lowestIgnoresUnknown() {
        assertEquals(ClauseConfidence.MEDIUM, ClauseConfidence.lowest(ClauseConfidence.UNKNOWN, ClauseConfidence.HIGH, ClauseConfidence.MEDIUM));
        assertEquals(ClauseConfidence.UNKNOWN, ClauseConfidence.lowest(ClauseConfidence.UNKNOWN));
    }
}
