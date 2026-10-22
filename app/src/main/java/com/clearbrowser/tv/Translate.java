package com.clearbrowser.tv;

import android.net.Uri;

import java.util.Locale;

/**
 * Page translation through Google Translate's page proxy: example.com/page opens as
 * example-com.translate.goog/page?_x_tr_sl=auto&_x_tr_tl=en, and links followed from there stay
 * translated. The proxied page lives on Google's address, so logins and some players don't carry
 * over; "Show the original page" goes back.
 */
final class Translate {
    private static final String SUFFIX = ".translate.goog";

    private Translate() { }

    static boolean isTranslated(String url) {
        String h = host(url);
        return h.endsWith(SUFFIX) || h.equals("translate.google.com") && url.contains("/translate");
    }

    /** The address that shows {@code url} in English (Google works out the proxy name). */
    static String toEnglish(String url) {
        return "https://translate.google.com/translate?sl=auto&tl=en&hl=en&u=" + Uri.encode(url);
    }

    /** The page behind a translated address: "www-my--site-com.translate.goog" is "www.my-site.com". */
    static String original(String url) {
        Uri u = Uri.parse(url);
        String h = host(url);
        if (h.equals("translate.google.com")) {
            String inner = u.getQueryParameter("u");
            return inner != null ? inner : url;
        }
        if (!h.endsWith(SUFFIX)) return url;
        String name = h.substring(0, h.length() - SUFFIX.length())
                .replace("--", "\u0000").replace('-', '.').replace('\u0000', '-');
        String scheme = u.getQueryParameter("_x_tr_sch");
        Uri.Builder b = new Uri.Builder().scheme(scheme == null ? "https" : scheme)
                .encodedAuthority(name).encodedPath(u.getEncodedPath()).encodedFragment(u.getEncodedFragment());
        for (String k : u.getQueryParameterNames()) {
            if (k.startsWith("_x_tr_")) continue;
            for (String v : u.getQueryParameters(k)) b.appendQueryParameter(k, v);
        }
        return b.build().toString();
    }

    private static String host(String url) {
        String h = url == null ? null : Uri.parse(url).getHost();
        return h == null ? "" : h.toLowerCase(Locale.ROOT);
    }

    /**
     * Run on a loaded page: its declared language and how much of its text is in a non-Latin
     * script, as JSON. Declared languages are often wrong ("en" on a Hindi site), so both count.
     */
    static final String DETECT_JS = "(function(){var l=(document.documentElement.lang||'').toLowerCase();"
            + "if(!l){var m=document.querySelector('meta[http-equiv=\"content-language\" i]');if(m)l=(m.content||'').toLowerCase();}"
            + "var t=((document.body&&document.body.innerText)||'').slice(0,4000),"
            + "all=t.match(/\\p{L}/gu)||[],lat=t.match(/\\p{Script=Latin}/gu)||[];"
            + "return JSON.stringify({lang:l,nonLatin:all.length>150?1-lat.length/all.length:0});})()";

    /** The language to offer translation from, or null for English (or too little text to say). */
    static String foreignLanguage(String detectJson) {
        try {
            String json = detectJson;
            if (json.startsWith("\"")) json = new org.json.JSONArray("[" + json + "]").getString(0); // evaluateJavascript quotes strings
            org.json.JSONObject o = new org.json.JSONObject(json);
            String lang = Lang.norm(o.optString("lang"));
            double nonLatin = o.optDouble("nonLatin", 0);
            if (!lang.isEmpty() && !lang.equals("en")) return Lang.name(lang);
            if (nonLatin > 0.5) return "another language";
        } catch (Exception ignored) { }
        return null;
    }
}
