package nie.translator.rtranslator.settings;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;

/**
 * Picks a translation model that fits this phone's memory and free storage.
 * The user can still choose another model on the same screen.
 */
public class ModelRecommendation {
    public final int translationModel;
    public final boolean whisperReducedRam;
    public final boolean useMozillaForVoice;
    public final int messageRes;
    public final String totalRamGb;
    public final String freeStorageGb;

    private ModelRecommendation(int translationModel, boolean whisperReducedRam, boolean useMozillaForVoice, int messageRes, String totalRamGb, String freeStorageGb) {
        this.translationModel = translationModel;
        this.whisperReducedRam = whisperReducedRam;
        this.useMozillaForVoice = useMozillaForVoice;
        this.messageRes = messageRes;
        this.totalRamGb = totalRamGb;
        this.freeStorageGb = freeStorageGb;
    }

    public static ModelRecommendation forDevice(Global global) {
        int totalMb = Math.max(global.getTotalRamSize(), 1);
        int systemMb = Math.max(0, totalMb - global.getMaxAllocatableRAM());
        long freeMb = global.getAvailableInternalMemorySize();
        boolean whisperReduced = totalMb <= 7000;
        int whisperMb = whisperReduced ? 500 : 900;
        int usedBase = systemMb + whisperMb;
        float redLine = 100f - (global.getRamThreshold() * 100f) / totalMb;

        // Index-Translate 2B is the quality default. TranslateGemma 4B is never picked for the user:
        // it needs about twice the memory and is slower per token.
        boolean indexFits = fits(usedBase + 1900, totalMb, redLine) && freeMb > 1600;
        boolean qwenFits = fits(usedBase + 1400, totalMb, redLine) && freeMb > 1200;
        int model;
        int message;
        int modelMb;
        if (indexFits) {
            model = Translator.INDEX_TRANSLATE;
            modelMb = 1900;
            message = R.string.model_recommendation_index;
        } else if (qwenFits) {
            model = Translator.QWEN35;
            modelMb = 1400;
            message = R.string.model_recommendation_qwen35;
        } else {
            model = Translator.MOZILLA;
            modelMb = 100;
            message = R.string.model_recommendation_mozilla;
        }

        boolean mozillaForVoice = model != Translator.MOZILLA && !fits(usedBase + modelMb, totalMb, redLine - 8f);
        return new ModelRecommendation(model, whisperReduced, mozillaForVoice, message, gb(totalMb), gb(freeMb));
    }

    private static boolean fits(int usedMb, int totalMb, float redLinePercent) {
        return (usedMb * 100f) / totalMb < redLinePercent;
    }

    private static String gb(long mb) {
        return String.format(java.util.Locale.US, "%.1f", mb / 1000f);
    }
}
