package com.metroticket_api.green;

/** Cấp Hành trình xanh theo tổng CO2 đã tiết kiệm (gram). */
public enum GreenLevel {
    SEED("Hạt mầm", 0),
    SPROUT("Chồi xanh", 5_000),
    SAPLING("Cây non", 25_000),
    TREE("Cây xanh", 100_000),
    FOREST("Rừng xanh", 500_000);

    private final String label;
    private final long minGrams;

    GreenLevel(String label, long minGrams) {
        this.label = label;
        this.minGrams = minGrams;
    }

    public String label() { return label; }
    public long minGrams() { return minGrams; }

    public static GreenLevel of(long grams) {
        GreenLevel level = SEED;
        for (GreenLevel l : values()) {
            if (grams >= l.minGrams) {
                level = l;
            }
        }
        return level;
    }

    public GreenLevel next() {
        return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
    }
}
