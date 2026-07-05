package com.custoking.ims.schoolcoreservice.infrastructure;

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

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final int MAX_REDIRECTS = 3;

    private final long maxBytes;
    private final boolean allowLoopbackForTest;

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
            if (isBlockedAddress(addr) && !allowLoopbackForTest) {
                throw new ImageFetchException("blocked_host", "host resolves to a blocked address");
            }
        }
    }

    static boolean isBlockedAddress(InetAddress addr) {
        return addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress() || addr.isMulticastAddress() || isUniqueLocalIpv6(addr);
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
