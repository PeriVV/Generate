package com.casc.unittestagent.llm;

import java.net.URI;
import java.util.regex.Pattern;

/** Reporting boundary: never export authentication values, URL credentials or query parameters. */
final class DiagnosticRedactor {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE);
    private final String key;
    DiagnosticRedactor(String key) { this.key = key == null ? "" : key.trim(); }

    String clean(String input) {
        String text = input == null ? "" : input;
        text = hideKeyFragments(text);
        text = text.replaceAll("(?i)(Bearer\\s+)[^\\s,;\\\"']+", "$1[已隐藏]")
                .replaceAll("(?i)((?:api[_-]?key|access[_-]?token|authorization|password)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;&\\\"']+", "$1[已隐藏]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]{6,}", "[密钥已隐藏]");
        var matcher = URL.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(endpoint(matcher.group())));
        matcher.appendTail(result);
        return result.toString().replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
    }

    String endpoint(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null) return "[地址无法解析]";
            String path = uri.getPath() == null ? "" : uri.getPath();
            path = hideKeyFragments(path);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), path, null, null).toString();
        } catch (Exception ignored) { return "[地址无法解析]"; }
    }

    String detail(String input) {
        String text = clean(input).replace('\n', ' ');
        return text.substring(0, Math.min(500, text.length()));
    }

    private String hideKeyFragments(String text) {
        if (key.isEmpty()) return text;
        if (key.length() < 8) return text.replace(key, "[密钥已隐藏]");
        // A bounded header/error may contain only a prefix of a long credential. Consume the
        // entire matching fragment, rather than masking a short prefix and exposing the rest.
        String prefix = key.substring(0, 8);
        StringBuilder safe = new StringBuilder(text.length());
        int from = 0, at;
        while ((at = text.indexOf(prefix, from)) >= 0) {
            safe.append(text, from, at);
            int matched = 8;
            while (matched < key.length() && at + matched < text.length()
                    && key.charAt(matched) == text.charAt(at + matched)) matched++;
            safe.append("[密钥已隐藏]"); from = at + matched;
        }
        return safe.append(text, from, text.length()).toString();
    }
}
