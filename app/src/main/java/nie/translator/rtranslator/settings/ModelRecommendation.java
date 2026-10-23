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
    public final boolean useDictionaries;
    public final boolean useTatoeba;
    public final boolean useMozillaForVoice;
    public final int messageRes;
    public final String totalRamGb;
    public final String freeStorageGb;

    private ModelRecommendation(int translationModel, boolean whisperReducedRam, boolean useDictionaries, boolean useTatoeba, boolean useMozillaForVoice, int messageRes, String totalRamGb, String freeStorageGb) {
        this.translationModel = translationModel;
        this.whisperReducedRam = whisperReducedRam;
        this.useDictionaries = useDictionaries;
        this.useTatoeba = useTatoeba;
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

        boolean gemmaFits = fits(usedBase + 3600, totalMb, redLine) && freeMb > 4200;
        boolean hyFits = fits(usedBase + 1900, totalMb, redLine) && freeMb > 2600;
        boolean madladFits = fits(usedBase + 1800, totalMb, redLine) && freeMb > 2200;
        int model;
        int message;
        int modelMb;
        if (gemmaFits) {
            model = Translator.TRANSLATEGEMMA;
            modelMb = 3600;
            message = R.string.model_recommendation_gemma;
        } else if (hyFits) {
            model = Translator.HY_MT;
            modelMb = 1900;
            message = R.string.model_recommendation_hy;
        } else if (madladFits) {
            model = Translator.MADLAD_CACHE;
            modelMb = 1800;
            message = R.string.model_recommendation_madlad;
        } else {
            model = Translator.MOZILLA;
            modelMb = 100;
            message = R.string.model_recommendation_mozilla;
        }

        boolean dictionaries = fits(usedBase + modelMb + 140, totalMb, redLine) && freeMb > modelMb + 800;
        boolean tatoeba = fits(usedBase + modelMb + 5, totalMb, redLine) && freeMb > modelMb + 1600;
        boolean mozillaForVoice = model != Translator.MOZILLA && !fits(usedBase + modelMb, totalMb, redLine - 8f);
        return new ModelRecommendation(model, whisperReduced, dictionaries, tatoeba, mozillaForVoice, message, gb(totalMb), gb(freeMb));
    }

    private static boolean fits(int usedMb, int totalMb, float redLinePercent) {
        return (usedMb * 100f) / totalMb < redLinePercent;
    }

    private static String gb(long mb) {
        return String.format(java.util.Locale.US, "%.1f", mb / 1000f);
    }
}
