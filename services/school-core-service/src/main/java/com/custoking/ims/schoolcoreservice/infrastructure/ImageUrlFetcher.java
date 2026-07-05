package com.custoking.ims.schoolcoreservice.infrastructure;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

@Component
public class ImageUrlFetcher {

    static {
        // SSRF rebinding mitigation (pragmatic): keep the positive DNS cache long enough that the
        // address validated in validateHost() is the one HttpClient connects to (they resolve ms apart).
        // Residual: a precisely-timed sub-TTL rebind is not fully closed (java.net.http has no
        // per-request resolver hook; a full fix would need a global InetAddressResolverProvider).
        java.security.Security.setProperty("networkaddress.cache.ttl", "30");
    }

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final int MAX_REDIRECTS = 3;

    private final long maxBytes;
    private final boolean allowLoopbackForTest;

    @Autowired
    public ImageUrlFetcher(@Value("${student.photo.max-bytes:2097152}") long maxBytes) {
        this(maxBytes, false);
    }
    private ImageUrlFetcher(long maxBytes, boolean allowLoopbackForTest) {
        this.maxBytes = maxBytes;
        this.allowLoopbackForTest = allowLoopbackForTest;
    }
    static ImageUrlFetcher forTestAllowingLoopback(long maxBytes) {
        return new ImageUrlFetcher(maxBytes, true);
    }

    public record FetchedImage(byte[] data, String contentType) {}

    public FetchedImage fetch(String rawUrl) {
        URI uri = parseHttp(rawUrl);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        int hops = 0;
        while (true) {
            validateHost(uri.getHost());
            HttpResponse<InputStream> resp;
            try {
                resp = client.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
            } catch (java.net.http.HttpTimeoutException e) {
                throw new ImageFetchException("timeout", "timed out fetching " + uri.getHost());
            } catch (Exception e) {
                throw new ImageFetchException("unreachable", "could not fetch: " + e.getMessage());
            }
            int code = resp.statusCode();
            if (code >= 300 && code < 400) {
                if (++hops > MAX_REDIRECTS) throw new ImageFetchException("unreachable", "too many redirects");
                String loc = resp.headers().firstValue("location")
                        .orElseThrow(() -> new ImageFetchException("unreachable", "redirect without location"));
                uri = uri.resolve(loc);
                if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                    throw new ImageFetchException("blocked_host", "redirect to non-http scheme");
                }
                continue;
            }
            if (code != 200) throw new ImageFetchException("unreachable", "HTTP " + code);
            String contentType = resp.headers().firstValue("content-type").orElse("")
                    .split(";")[0].trim().toLowerCase();
            if (!IMAGE_TYPES.contains(contentType)) {
                throw new ImageFetchException("not_an_image", "content-type " + contentType);
            }
            byte[] data = readBounded(resp.body());
            return new FetchedImage(data, contentType);
        }
    }

    private URI parseHttp(String rawUrl) {
        URI uri;
        try { uri = URI.create(rawUrl.trim()); } catch (RuntimeException e) {
            throw new ImageFetchException("invalid_url", "malformed url");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new ImageFetchException("invalid_url", "only http(s) urls are allowed");
        }
        return uri;
    }

    private void validateHost(String host) {
        InetAddress[] addrs;
        try { addrs = InetAddress.getAllByName(host); } catch (Exception e) {
            throw new ImageFetchException("unreachable", "cannot resolve host");
        }
        for (InetAddress addr : addrs) {
            if (isBlockedAddress(addr)) {
                if (allowLoopbackForTest && addr.isLoopbackAddress()) continue;
                throw new ImageFetchException("blocked_host", "host resolves to a blocked address");
            }
        }
    }

    static boolean isBlockedAddress(InetAddress addr) {
        InetAddress effective = unwrapEmbeddedIpv4(addr);
        if (effective != addr) return isBlockedAddress(effective);
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress() || addr.isMulticastAddress() || isUniqueLocalIpv6(addr)) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int o0 = b[0] & 0xFF, o1 = b[1] & 0xFF, o2 = b[2] & 0xFF;
            if (o0 == 0) return true;                                   // 0.0.0.0/8
            if (o0 == 100 && (o1 & 0xC0) == 0x40) return true;         // 100.64.0.0/10 CGNAT
            if (o0 == 192 && o1 == 0 && (o2 == 0 || o2 == 2)) return true; // 192.0.0.0/24, 192.0.2.0/24
            if (o0 == 198 && (o1 & 0xFE) == 18) return true;          // 198.18.0.0/15
            if (o0 == 198 && o1 == 51 && o2 == 100) return true;      // 198.51.100.0/24
            if (o0 == 203 && o1 == 0 && o2 == 113) return true;       // 203.0.113.0/24
            if (o0 >= 240) return true;                                // 240.0.0.0/4 + broadcast
        }
        return false;
    }

    private static InetAddress unwrapEmbeddedIpv4(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length != 16) return addr;
        boolean mapped = true;
        for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
        if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) return ipv4(b, 12); // ::ffff:0:0/96
        if ((b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
            boolean zeros = true;
            for (int i = 4; i < 12; i++) if (b[i] != 0) { zeros = false; break; }
            if (zeros) return ipv4(b, 12);                             // 64:ff9b::/96 NAT64
        }
        return addr;
    }

    private static InetAddress ipv4(byte[] b, int off) {
        try { return InetAddress.getByAddress(new byte[]{b[off], b[off + 1], b[off + 2], b[off + 3]}); }
        catch (java.net.UnknownHostException e) { throw new IllegalStateException(e); }
    }

    private static boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC; // fc00::/7
    }

    private byte[] readBounded(InputStream in) {
        try (in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) throw new ImageFetchException("too_large", "image exceeds " + maxBytes + " bytes");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (ImageFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageFetchException("unreachable", "read error: " + e.getMessage());
        }
    }
}
