package com.riley.analogfix.cache;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UrlKeys {
    private static final Pattern YT_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern YT_QUERY_V = Pattern.compile("(?:^|&)v=([A-Za-z0-9_-]{11})");

    private UrlKeys() {
    }

    public static String key(String url) {
        return sha1(normalize(url));
    }

    static String normalize(String url) {
        String trimmed = url.trim();
        String videoId = youtubeId(trimmed);
        return videoId != null ? "youtube:" + videoId : trimmed;
    }

    private static String youtubeId(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();

        if (host.equals("youtu.be")) {
            return idOrNull(firstSegment(path));
        }
        if (!host.equals("youtube.com") && !host.endsWith(".youtube.com")) {
            return null;
        }
        if (path.equals("/watch") && uri.getRawQuery() != null) {
            Matcher m = YT_QUERY_V.matcher(uri.getRawQuery());
            return m.find() ? m.group(1) : null;
        }
        for (String prefix : new String[] { "/shorts/", "/embed/", "/live/", "/v/" }) {
            if (path.startsWith(prefix)) {
                return idOrNull(firstSegment(path.substring(prefix.length() - 1)));
            }
        }
        return null;
    }

    private static String firstSegment(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        int slash = p.indexOf('/');
        return slash >= 0 ? p.substring(0, slash) : p;
    }

    private static String idOrNull(String candidate) {
        return YT_ID.matcher(candidate).matches() ? candidate : null;
    }

    private static String sha1(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
