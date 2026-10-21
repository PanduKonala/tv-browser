package com.clearbrowser.tv;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Language codes for the player's track lists: normalised codes, English names, and a flag. */
final class Lang {
    private static final Map<String, String> ISO3 = new HashMap<>();
    /** The country whose flag usually stands for a language. */
    private static final Map<String, String> FLAG = new HashMap<>();

    static {
        for (Locale l : Locale.getAvailableLocales()) {
            try { ISO3.put(l.getISO3Language(), l.getLanguage()); } catch (Exception ignored) { }
        }
        // Bibliographic ISO 639-2 codes some files use.
        ISO3.put("fre", "fr"); ISO3.put("ger", "de"); ISO3.put("chi", "zh"); ISO3.put("dut", "nl");
        ISO3.put("gre", "el"); ISO3.put("per", "fa"); ISO3.put("cze", "cs"); ISO3.put("rum", "ro");
        ISO3.put("slo", "sk"); ISO3.put("alb", "sq"); ISO3.put("arm", "hy"); ISO3.put("baq", "eu");
        ISO3.put("bur", "my"); ISO3.put("geo", "ka"); ISO3.put("ice", "is"); ISO3.put("mac", "mk");
        ISO3.put("mao", "mi"); ISO3.put("may", "ms"); ISO3.put("tib", "bo"); ISO3.put("wel", "cy");
        String[][] f = {
                {"en", "US"}, {"es", "ES"}, {"fr", "FR"}, {"de", "DE"}, {"it", "IT"}, {"pt", "PT"}, {"ar", "SA"},
                {"bn", "IN"}, {"bg", "BG"}, {"ca", "ES"}, {"zh", "CN"}, {"hi", "IN"}, {"ta", "IN"}, {"te", "IN"},
                {"ml", "IN"}, {"kn", "IN"}, {"mr", "IN"}, {"gu", "IN"}, {"pa", "IN"}, {"ur", "PK"}, {"ru", "RU"},
                {"ja", "JP"}, {"ko", "KR"}, {"tr", "TR"}, {"nl", "NL"}, {"sv", "SE"}, {"no", "NO"}, {"nb", "NO"},
                {"da", "DK"}, {"fi", "FI"}, {"pl", "PL"}, {"cs", "CZ"}, {"el", "GR"}, {"he", "IL"}, {"iw", "IL"},
                {"hu", "HU"}, {"id", "ID"}, {"in", "ID"}, {"ms", "MY"}, {"th", "TH"}, {"vi", "VN"}, {"uk", "UA"},
                {"ro", "RO"}, {"hr", "HR"}, {"sr", "RS"}, {"sk", "SK"}, {"sl", "SI"}, {"fa", "IR"}, {"et", "EE"},
                {"lv", "LV"}, {"lt", "LT"}, {"is", "IS"}, {"ga", "IE"}, {"sq", "AL"}, {"mk", "MK"}, {"bs", "BA"},
                {"ka", "GE"}, {"hy", "AM"}, {"az", "AZ"}, {"kk", "KZ"}, {"uz", "UZ"}, {"ne", "NP"}, {"si", "LK"},
                {"km", "KH"}, {"lo", "LA"}, {"my", "MM"}, {"tl", "PH"}, {"fil", "PH"}, {"sw", "KE"}, {"af", "ZA"},
                {"eu", "ES"}, {"gl", "ES"}, {"cy", "GB"}, {"mn", "MN"},
        };
        for (String[] p : f) FLAG.put(p[0], p[1]);
    }

    private Lang() { }

    /** "eng" / "en-US" / "EN" -> "en"; "" when unknown. */
    static String norm(String code) {
        if (code == null) return "";
        String c = code.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = c.indexOf('-');
        if (dash > 0) c = c.substring(0, dash);
        if (c.isEmpty() || c.equals("und") || c.equals("unknown")) return "";
        if (c.length() == 3 && ISO3.containsKey(c)) return ISO3.get(c);
        return c;
    }

    /** English name of a language code, or "" if unknown. */
    static String name(String code) {
        String c = norm(code);
        if (c.isEmpty()) return "";
        String n = new Locale(c).getDisplayLanguage(Locale.ENGLISH);
        return n == null || n.equalsIgnoreCase(c) ? code : n;
    }

    /** A flag emoji for the language, or a globe when there's no usual one. */
    static String flag(String code) {
        String cc = FLAG.get(norm(code));
        if (cc == null) return "🌐";
        int a = 0x1F1E6 - 'A';
        return new String(Character.toChars(a + cc.charAt(0))) + new String(Character.toChars(a + cc.charAt(1)));
    }
}
