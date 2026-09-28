/*
 * SHILLGRAM: SHILLVPN built into the app.
 */
package io.github.audit0.shillgram.vpn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The subscription as Xray sees it: the token of a pasted link, the share
 * links of a fetched subscription body, and the Xray config serving a local
 * SOCKS5 port for Telegram. A port of the desktop client
 * (shillgramm/shill_vpn.cpp: TokenFromLink, Outbound, HysteriaOutbound,
 * applyBody, buildConfig); plain Java so it is checked on a JVM.
 *
 * Nothing here logs: the links and bodies are secrets.
 */
public final class XrayConfig {

    public static final String SITE = "https://shillvpn.site";

    private static final Pattern TOKEN = Pattern.compile(
            "(?:^|/)([0-9a-f]{24}\\.[0-9a-f]{64})(?:$|[/?#])");
    private static final Pattern EXPIRE = Pattern.compile("expire=(\\d+)");

    private XrayConfig() {
    }

    /** One usable server of the subscription. */
    public static final class Entry {
        public final String title;
        public final String uri;

        Entry(String title, String uri) {
            this.title = title;
            this.uri = uri;
        }
    }

    // ---- Links.

    /** Accepts /sub/, /c/, /r/<app>/ links or the bare token. */
    public static String tokenFromLink(String link) {
        if (link == null) {
            return null;
        }
        final Matcher matcher = TOKEN.matcher(link.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Where the subscription body is served for this link and token. */
    public static String subscriptionUrl(String link, String token) {
        final Url url = Url.parse(link == null ? "" : link.trim());
        if (url != null
                && "https".equals(url.scheme)
                && url.path.contains("/sub/")
                && url.path.endsWith(token)) {
            return url.withoutQueryAndFragment();
        }
        final String origin = (url != null && "https".equals(url.scheme) && !url.host.isEmpty())
                ? ("https://" + url.host)
                : SITE;
        return origin + "/sub/" + token;
    }

    /** Unix time from the subscription-userinfo header, 0 when absent. */
    public static long expireFromUserInfo(String header) {
        if (header == null) {
            return 0;
        }
        final Matcher matcher = EXPIRE.matcher(header);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- Subscription body.

    /** The share links of a body this app can use; empty when none. */
    public static List<Entry> parseBody(byte[] body) {
        if (body == null) {
            return Collections.emptyList();
        }
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (!text.contains("://")) {
            final String compact = text.replace("\n", "").replace("\r", "");
            final byte[] decoded = decodeBase64(compact);
            if (decoded != null) {
                text = new String(decoded, StandardCharsets.UTF_8);
            }
        }
        final List<Entry> result = new ArrayList<>();
        for (String line : text.split("\n")) {
            final String uri = line.trim();
            if (outbound(uri, "p") != null) {
                final Url url = Url.parse(uri);
                result.add(new Entry(url != null ? url.fragment : "", uri));
            }
        }
        return result;
    }

    /** Standard or URL-safe Base64, padding optional; null when invalid. */
    static byte[] decodeBase64(String text) {
        final int length = text.length();
        final byte[] out = new byte[length * 3 / 4 + 3];
        int size = 0;
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < length; i++) {
            final char c = text.charAt(i);
            final int value;
            if (c >= 'A' && c <= 'Z') {
                value = c - 'A';
            } else if (c >= 'a' && c <= 'z') {
                value = c - 'a' + 26;
            } else if (c >= '0' && c <= '9') {
                value = c - '0' + 52;
            } else if (c == '+' || c == '-') {
                value = 62;
            } else if (c == '/' || c == '_') {
                value = 63;
            } else if (c == '=') {
                break;
            } else if (c == ' ' || c == '\t') {
                continue;
            } else {
                return null;
            }
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[size++] = (byte) ((buffer >> bits) & 0xFF);
            }
        }
        final byte[] result = new byte[size];
        System.arraycopy(out, 0, result, 0, size);
        return result;
    }

    // ---- Xray outbounds.

    /**
     * One share link as an Xray outbound; null for the info lines of the
     * list and for what the bundled core does not open.
     */
    public static Map<String, Object> outbound(String uri, String tag) {
        final Url url = Url.parse(uri);
        if (url == null) {
            return null;
        } else if ("hysteria2".equals(url.scheme) || "hy2".equals(url.scheme)) {
            return hysteriaOutbound(url, tag);
        } else if (!"vless".equals(url.scheme)) {
            return null;
        }
        final String host = url.host;
        final int port = url.port;
        final String id = url.userName;
        if (host.isEmpty()
                || "0.0.0.0".equals(host) // Info lines: "3 дня осталось" and such.
                || port <= 0
                || id.length() != 36) {
            return null;
        }
        final String security = url.query("security");
        final String network = url.query("type").isEmpty() ? "tcp" : url.query("type");
        if (!"tcp".equals(network) && !"xhttp".equals(network)) {
            return null;
        }
        final Map<String, Object> user = Json.object(
                "id", id,
                "encryption", "none");
        if (!url.query("flow").isEmpty()) {
            user.put("flow", url.query("flow"));
        }
        final Map<String, Object> stream = Json.object("network", network);
        final String fingerprint = url.query("fp").isEmpty() ? "chrome" : url.query("fp");
        if ("reality".equals(security)) {
            stream.put("security", "reality");
            stream.put("realitySettings", Json.object(
                    "serverName", url.query("sni"),
                    "fingerprint", fingerprint,
                    "publicKey", url.query("pbk"),
                    "shortId", url.query("sid")));
        } else if ("tls".equals(security)) {
            stream.put("security", "tls");
            stream.put("tlsSettings", Json.object(
                    "serverName", url.query("sni").isEmpty() ? host : url.query("sni"),
                    "fingerprint", fingerprint));
        } else {
            return null; // Plain VLESS is not something we serve.
        }
        if ("xhttp".equals(network)) {
            final Map<String, Object> xhttp = new LinkedHashMap<>();
            if (!url.query("path").isEmpty()) {
                xhttp.put("path", url.query("path"));
            }
            if (!url.query("host").isEmpty()) {
                xhttp.put("host", url.query("host"));
            }
            if (!url.query("mode").isEmpty()) {
                xhttp.put("mode", url.query("mode"));
            }
            final Map<String, Object> extra = Json.parseObject(url.query("extra"));
            if (extra != null) {
                xhttp.put("extra", extra);
            }
            stream.put("xhttpSettings", xhttp);
        }
        return Json.object(
                "tag", tag,
                "protocol", "vless",
                "settings", Json.object(
                        "vnext", Json.array(Json.object(
                                "address", host,
                                "port", port,
                                "users", Json.array(user)))),
                "streamSettings", stream);
    }

    /**
     * Hysteria 2 (QUIC). Xray 26.9 names it "hysteria", version 2;
     * "insecure" is gone from Xray, only a pinned certificate replaces the
     * name check.
     */
    private static Map<String, Object> hysteriaOutbound(Url url, String tag) {
        final String host = url.host;
        final int port = url.port;
        final String auth = url.userInfo;
        if (host.isEmpty() || "0.0.0.0".equals(host) || port <= 0 || auth.isEmpty()) {
            return null;
        }
        if (!url.query("obfs").isEmpty()) {
            return null; // Salamander is not in the bundled core.
        }
        final Map<String, Object> tls = Json.object(
                "serverName", url.query("sni").isEmpty() ? host : url.query("sni"),
                "alpn", Json.array("h3"));
        final String pin = url.query("pinSHA256");
        if (!pin.isEmpty()) {
            tls.put("pinnedPeerCertSha256", pin);
        } else if ("1".equals(url.query("insecure"))) {
            return null;
        }
        return Json.object(
                "tag", tag,
                "protocol", "hysteria",
                "settings", Json.object(
                        "version", 2,
                        "address", host,
                        "port", port),
                "streamSettings", Json.object(
                        "network", "hysteria",
                        "security", "tls",
                        "tlsSettings", tls,
                        "hysteriaSettings", Json.object(
                                "version", 2,
                                "auth", auth)));
    }

    // ---- The whole config.

    /**
     * The Xray config: a SOCKS5 inbound on 127.0.0.1:port with a login and
     * password, every entry as an outbound and, with several of them, a
     * leastPing balancer. No access log.
     */
    public static String build(List<Entry> entries, int port, String user, String password) {
        final List<Object> outbounds = new ArrayList<>();
        int index = 0;
        for (Entry entry : entries) {
            final Map<String, Object> outbound = outbound(entry.uri, "p" + index);
            if (outbound != null) {
                outbounds.add(outbound);
                ++index;
            }
        }
        final Map<String, Object> config = Json.object(
                // No access log: SHILLVPN keeps no history of connections.
                "log", Json.object(
                        "loglevel", "warning",
                        "access", "none"),
                "inbounds", Json.array(Json.object(
                        "tag", "telegram",
                        "listen", "127.0.0.1",
                        "port", port,
                        "protocol", "socks",
                        "settings", Json.object(
                                "auth", "password",
                                "accounts", Json.array(Json.object(
                                        "user", user,
                                        "pass", password)),
                                "udp", false))),
                "outbounds", outbounds);
        if (index > 1) {
            // Several entries: the core probes them and takes the quickest
            // one that answers, so a cut protocol does not stop Telegram.
            config.put("observatory", Json.object(
                    "subjectSelector", Json.array("p"),
                    "probeURL", "https://www.gstatic.com/generate_204",
                    "probeInterval", "2m",
                    "enableConcurrency", true));
            config.put("routing", Json.object(
                    "balancers", Json.array(Json.object(
                            "tag", "auto",
                            "selector", Json.array("p"),
                            "strategy", Json.object("type", "leastPing"),
                            "fallbackTag", "p0")),
                    "rules", Json.array(Json.object(
                            "type", "field",
                            "inboundTag", Json.array("telegram"),
                            "balancerTag", "auto"))));
        }
        return Json.write(config);
    }

    // ---- A share link, split the way QUrl does for these fields.

    static final class Url {
        String scheme = "";
        String userInfo = ""; // Decoded.
        String userName = ""; // Decoded, before ':'.
        String host = "";
        int port = -1;
        String path = "";
        String rawQuery = "";
        String fragment = ""; // Decoded.
        private String base = "";

        static Url parse(String text) {
            if (text == null || text.isEmpty()) {
                return null;
            }
            final int schemeEnd = text.indexOf("://");
            if (schemeEnd <= 0) {
                return null;
            }
            final Url url = new Url();
            url.scheme = text.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            for (int i = 0; i < url.scheme.length(); i++) {
                final char c = url.scheme.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) {
                    return null;
                }
            }
            String rest = text.substring(schemeEnd + 3);
            final int hash = rest.indexOf('#');
            if (hash >= 0) {
                url.fragment = decode(rest.substring(hash + 1));
                rest = rest.substring(0, hash);
            }
            if (url.fragment == null) {
                return null;
            }
            final int question = rest.indexOf('?');
            if (question >= 0) {
                url.rawQuery = rest.substring(question + 1);
                rest = rest.substring(0, question);
            }
            final int slash = rest.indexOf('/');
            String authority = rest;
            if (slash >= 0) {
                url.path = rest.substring(slash);
                authority = rest.substring(0, slash);
            }
            final int at = authority.lastIndexOf('@');
            if (at >= 0) {
                final String info = authority.substring(0, at);
                authority = authority.substring(at + 1);
                final String decoded = decode(info);
                if (decoded == null) {
                    return null;
                }
                url.userInfo = decoded;
                final int colon = info.indexOf(':');
                url.userName = decode(colon >= 0 ? info.substring(0, colon) : info);
                if (url.userName == null) {
                    return null;
                }
            }
            String hostPart = authority;
            String portPart = null;
            if (authority.startsWith("[")) {
                final int close = authority.indexOf(']');
                if (close < 0) {
                    return null;
                }
                hostPart = authority.substring(1, close);
                final String after = authority.substring(close + 1);
                if (after.startsWith(":")) {
                    portPart = after.substring(1);
                } else if (!after.isEmpty()) {
                    return null;
                }
            } else {
                final int colon = authority.lastIndexOf(':');
                if (colon >= 0) {
                    hostPart = authority.substring(0, colon);
                    portPart = authority.substring(colon + 1);
                }
                if (hostPart.contains(":")) {
                    return null;
                }
            }
            for (int i = 0; i < hostPart.length(); i++) {
                final char c = hostPart.charAt(i);
                if (c <= ' ' || c == '/' || c == '\\' || c == '?' || c == '#' || c == '@' || c == '%') {
                    return null;
                }
            }
            url.host = hostPart.toLowerCase(Locale.ROOT);
            if (portPart != null && !portPart.isEmpty()) {
                try {
                    url.port = Integer.parseInt(portPart);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (url.port < 0 || url.port > 65535) {
                    return null;
                }
            }
            final String userPrefix = at >= 0 ? text.substring(schemeEnd + 3, schemeEnd + 3 + at + 1) : "";
            url.base = url.scheme + "://" + userPrefix + authority + url.path;
            return url;
        }

        String withoutQueryAndFragment() {
            return base;
        }

        /** The first value of a query item, percent-decoded ('+' kept). */
        String query(String key) {
            if (rawQuery.isEmpty()) {
                return "";
            }
            for (String item : rawQuery.split("&")) {
                final int equals = item.indexOf('=');
                final String name = decode(equals >= 0 ? item.substring(0, equals) : item);
                if (key.equals(name)) {
                    final String value = decode(equals >= 0 ? item.substring(equals + 1) : "");
                    return value != null ? value : "";
                }
            }
            return "";
        }

        /** Percent-decoding as UTF-8; null on a broken escape. */
        static String decode(String text) {
            if (text.indexOf('%') < 0) {
                return text;
            }
            final byte[] out = new byte[text.length() * 3];
            int size = 0;
            for (int i = 0; i < text.length(); i++) {
                final char c = text.charAt(i);
                if (c == '%') {
                    if (i + 2 >= text.length()) {
                        return null;
                    }
                    final int high = Character.digit(text.charAt(i + 1), 16);
                    final int low = Character.digit(text.charAt(i + 2), 16);
                    if (high < 0 || low < 0) {
                        return null;
                    }
                    out[size++] = (byte) ((high << 4) | low);
                    i += 2;
                } else {
                    final byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                    if (Character.isHighSurrogate(c) && i + 1 < text.length()) {
                        final byte[] pair = text.substring(i, i + 2).getBytes(StandardCharsets.UTF_8);
                        System.arraycopy(pair, 0, out, size, pair.length);
                        size += pair.length;
                        i++;
                        continue;
                    }
                    System.arraycopy(bytes, 0, out, size, bytes.length);
                    size += bytes.length;
                }
            }
            return new String(out, 0, size, StandardCharsets.UTF_8);
        }
    }
}
