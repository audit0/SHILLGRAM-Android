/*
 * SHILLGRAM: a plain-JVM check of the SHILLVPN parser and Xray config
 * builder against synthetic (fake) share links. Run scripts/vpn_selftest.sh.
 * Nothing here is a real server, token or key.
 */
import io.github.audit0.shillgram.vpn.Json;
import io.github.audit0.shillgram.vpn.TrialWork;
import io.github.audit0.shillgram.vpn.XrayConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public final class VpnSelfTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        final String token = "0123456789abcdef01234567." + "ab".repeat(32);

        // Tokens from the link forms the desktop accepts.
        check(token.equals(XrayConfig.tokenFromLink("https://example.test/sub/" + token)), "token from /sub/ link");
        check(token.equals(XrayConfig.tokenFromLink("https://example.test/c/" + token + "?x=1")), "token from /c/ link with query");
        check(token.equals(XrayConfig.tokenFromLink("  " + token + "  ")), "bare token");
        check(XrayConfig.tokenFromLink("https://example.test/sub/" + token + "x") == null, "token with junk after it is rejected");
        check(XrayConfig.tokenFromLink("hello") == null, "not a link");

        check(("https://example.test/sub/" + token).equals(
                XrayConfig.subscriptionUrl("https://example.test/sub/" + token + "?a=b#c", token)), "subscription url keeps a /sub/ link, drops query");
        check(("https://example.test/sub/" + token).equals(
                XrayConfig.subscriptionUrl("https://example.test/c/" + token, token)), "subscription url from /c/ link uses its host");
        check(("https://shillvpn.site/sub/" + token).equals(
                XrayConfig.subscriptionUrl(token, token)), "bare token goes to shillvpn.site");
        check(XrayConfig.expireFromUserInfo("upload=0; download=0; total=0; expire=1790000000") == 1790000000L, "expire from subscription-userinfo");

        // A synthetic subscription: REALITY/TCP, XHTTP via CDN (tls), Hysteria 2,
        // an info line (0.0.0.0), plain VLESS (not served), vmess (not opened).
        final String uuid = "11111111-2222-3333-4444-555555555555";
        final String reality = "vless://" + uuid + "@203.0.113.10:443?security=reality&type=tcp&flow=xtls-rprx-vision"
                + "&sni=www.example.com&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123abcd#%D0%9D%D0%B8%D0%B4%D0%B5%D1%80%D0%BB%D0%B0%D0%BD%D0%B4%D1%8B";
        final String xhttp = "vless://" + uuid + "@cdn.example.test:443?security=tls&type=xhttp&sni=cdn.example.test"
                + "&path=%2Fxh&host=cdn.example.test&mode=packet-up&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D#CDN";
        final String hy2 = "hysteria2://secret-auth@198.51.100.7:8443?sni=hy.example.test&pinSHA256=" + "cd".repeat(32) + "#HY2";
        final String info = "vless://" + uuid + "@0.0.0.0:1?security=reality&type=tcp#3%20%D0%B4%D0%BD%D1%8F";
        final String plain = "vless://" + uuid + "@203.0.113.11:80?type=tcp#plain";
        final String vmess = "vmess://eyJhZGQiOiIxLjIuMy40In0=";
        final String list = String.join("\n", reality, xhttp, hy2, info, plain, vmess) + "\n";
        final byte[] body = Base64.getEncoder().encode(list.getBytes(StandardCharsets.UTF_8));

        final List<XrayConfig.Entry> entries = XrayConfig.parseBody(body);
        check(entries.size() == 3, "base64 body: 3 usable entries (got " + entries.size() + ")");
        check(entries.size() > 0 && "Нидерланды".equals(entries.get(0).title), "entry title decoded from fragment");
        check(XrayConfig.parseBody(list.getBytes(StandardCharsets.UTF_8)).size() == 3, "plain-text body parses the same");
        final byte[] urlSafe = Base64.getUrlEncoder().withoutPadding().encode(list.getBytes(StandardCharsets.UTF_8));
        check(XrayConfig.parseBody(urlSafe).size() == 3, "url-safe base64 without padding");
        check(XrayConfig.parseBody("not a subscription".getBytes(StandardCharsets.UTF_8)).isEmpty(), "junk body gives no entries");

        final String config = XrayConfig.build(entries, 10808, "shilltest", "pass-word");
        final Map<String, Object> root = Json.parseObject(config);
        check(root != null, "config is valid JSON");
        final Map<String, Object> log = Json.child(root, "log");
        check("none".equals(Json.string(log, "access")), "no access log");
        final Map<String, Object> inbound = (Map<String, Object>) Json.list(root, "inbounds").get(0);
        check("127.0.0.1".equals(Json.string(inbound, "listen")) && Json.number(inbound, "port") == 10808
                && "socks".equals(Json.string(inbound, "protocol")), "socks inbound on 127.0.0.1");
        final Map<String, Object> socks = Json.child(inbound, "settings");
        check("password".equals(Json.string(socks, "auth")) && !Json.bool(socks, "udp"), "socks auth=password, udp off");
        final List<Object> outbounds = Json.list(root, "outbounds");
        check(outbounds.size() == 3, "3 outbounds");
        final Map<String, Object> o0 = (Map<String, Object>) outbounds.get(0);
        final Map<String, Object> s0 = Json.child(o0, "streamSettings");
        check("p0".equals(Json.string(o0, "tag")) && "reality".equals(Json.string(s0, "security"))
                && "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".equals(Json.string(Json.child(s0, "realitySettings"), "publicKey")),
                "reality outbound");
        final Map<String, Object> user0 = (Map<String, Object>) Json.list(
                (Map<String, Object>) Json.list(Json.child(o0, "settings"), "vnext").get(0), "users").get(0);
        check("xtls-rprx-vision".equals(Json.string(user0, "flow")) && "none".equals(Json.string(user0, "encryption")), "vless user flow");
        final Map<String, Object> s1 = Json.child((Map<String, Object>) outbounds.get(1), "streamSettings");
        final Map<String, Object> xh = Json.child(s1, "xhttpSettings");
        check("xhttp".equals(Json.string(s1, "network")) && "/xh".equals(Json.string(xh, "path"))
                && "100-1000".equals(Json.string(Json.child(xh, "extra"), "xPaddingBytes")), "xhttp settings with extra object");
        final Map<String, Object> o2 = (Map<String, Object>) outbounds.get(2);
        check("hysteria".equals(Json.string(o2, "protocol"))
                && "cd".repeat(32).equals(Json.string(Json.child(Json.child(o2, "streamSettings"), "tlsSettings"), "pinnedPeerCertSha256"))
                && "secret-auth".equals(Json.string(Json.child(Json.child(o2, "streamSettings"), "hysteriaSettings"), "auth")),
                "hysteria 2 outbound with pinned cert");
        check(root.containsKey("observatory") && root.containsKey("routing"), "several entries: observatory + leastPing balancer");
        final String single = XrayConfig.build(entries.subList(0, 1), 10808, "shilltest", "pw");
        check(!Json.parseObject(single).containsKey("routing"), "one entry: no balancer");

        // Hysteria with insecure=1 and no pin is refused, as on desktop.
        check(XrayConfig.outbound("hysteria2://a@198.51.100.7:8443?insecure=1", "p") == null, "insecure hysteria refused");
        check(XrayConfig.outbound("vless://short@203.0.113.10:443?security=tls", "p") == null, "bad uuid refused");

        // JSON round trip of escapes.
        final String tricky = "a\"b\\c\nd\u0001é";
        check(tricky.equals(Json.string(Json.parseObject(Json.write(Json.object("k", tricky))), "k")), "json string escapes round trip");

        // Proof of work: a quick check that a solution has 24 zero bits.
        final byte[] prefix = TrialWork.prefix(TrialWork.deviceHash("fake-device".getBytes(StandardCharsets.UTF_8)), 480000);
        final long started = System.currentTimeMillis();
        final String work = TrialWork.solve(prefix);
        final MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(prefix);
        sha.update(work.getBytes(StandardCharsets.US_ASCII));
        final byte[] hash = sha.digest();
        check(hash[0] == 0 && hash[1] == 0 && hash[2] == 0, "proof of work solved in " + (System.currentTimeMillis() - started) + " ms");

        if (args.length > 0) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args[0]), config.getBytes(StandardCharsets.UTF_8));
        }
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
