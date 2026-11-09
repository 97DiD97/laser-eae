package dev.eyesky;

import java.util.Locale;

/** Эмоции глаза. Кадр с номером ordinal() лежит в листе textures/item/s.png (сетка 4x2). */
public enum Emotion {
    NORMAL, BLINK, HAPPY, ANGRY, SAD, SURPRISED, SLEEPY;

    /** ID модели предмета в ресурспаке (assets/eyesky/items/N.json, N = номер эмоции). */
    public String modelId() {
        return "eyesky:" + ordinal();
    }

    public static Emotion parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
