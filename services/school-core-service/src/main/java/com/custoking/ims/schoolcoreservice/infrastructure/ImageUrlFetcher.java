package com.custoking.ims.schoolcoreservice.infrastructure;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.util.Set;

@Component
public class ImageUrlFetcher {

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final int MAX_REDIRECTS = 3;

    private final long maxBytes;
    private final boolean allowLoopbackForTest;

    @Autowired
    public ImageUrlFetcher(@Value("${student.photo.max-bytes:5242880}") long maxBytes) {
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

    private static final java.util.concurrent.Semaphore FETCH_SLOTS = new java.util.concurrent.Semaphore(8);
    private static final java.util.Map<String, long[]> QUOTAS = new java.util.HashMap<>();
    private static final java.util.concurrent.ExecutorService DNS_LOOKUPS = new java.util.concurrent.ThreadPoolExecutor(
            8, 8, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "image-fetch-dns"); thread.setDaemon(true); return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES = java.util.concurrent.Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "image-fetch-deadline"); thread.setDaemon(true); return thread;
    });

    public FetchedImage fetch(String rawUrl) {
        consumeAuthenticatedQuota();
        if (!FETCH_SLOTS.tryAcquire()) throw new ImageFetchException("busy", "Photo fetching is busy; retry later");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        try { return fetchWithinDeadline(rawUrl, deadline); }
        finally { FETCH_SLOTS.release(); }
    }

    private static synchronized void consumeAuthenticatedQuota() {
        var context = com.custoking.ims.schoolcoreservice.security.TenantContext.get();
        if (context.userId() == null) return; // Controllers enforce authentication; test fetchers have no actor.
        long window = System.currentTimeMillis() / 60_000;
        QUOTAS.entrySet().removeIf(entry -> entry.getValue()[0] != window);
        String actor = "user:" + context.userId();
        String school = "school:" + context.schoolId();
        if (QUOTAS.size() >= 4096 && (!QUOTAS.containsKey(actor) || !QUOTAS.containsKey(school)))
            throw new ImageFetchException("busy", "Photo fetch quota is busy; retry later");
        long[] userBudget = QUOTAS.computeIfAbsent(actor, key -> new long[]{window, 0});
        long[] schoolBudget = QUOTAS.computeIfAbsent(school, key -> new long[]{window, 0});
        if (userBudget[1] >= 20 || schoolBudget[1] >= 60) throw new ImageFetchException("busy", "Photo fetch quota exceeded; retry later");
        userBudget[1]++; schoolBudget[1]++;
    }

    private FetchedImage fetchWithinDeadline(String rawUrl, long deadline) {
        URI uri = parseHttp(rawUrl, deadline);
        // Socket DNS resolution itself is validated, including every redirect. There is no
        // unvalidated second resolution between this resolver and the connection.
        var manager = org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new org.apache.hc.client5.http.DnsResolver() {
                    public InetAddress[] resolve(String host) throws java.net.UnknownHostException { return validatedAddresses(host, deadline); }
                    public String resolveCanonicalHostname(String host) { return host; }
                }).build();
        try (var client = org.apache.hc.client5.http.impl.classic.HttpClients.custom()
                .setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries().build()) {
            int hops = 0;
            while (true) {
                validatedAddresses(uri.getHost(), deadline);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new ImageFetchException("timeout", "Photo fetching timed out");
                var request = new org.apache.hc.client5.http.classic.methods.HttpGet(uri);
                var timeout = org.apache.hc.core5.util.Timeout.ofNanoseconds(remaining);
                request.setConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                        .setConnectTimeout(timeout).setConnectionRequestTimeout(timeout).setResponseTimeout(timeout).build());
                var cancellation = DEADLINES.schedule(request::cancel, remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                try (var response = client.execute(request)) {
                    int code = response.getCode();
                    if (code >= 300 && code < 400) {
                        if (++hops > MAX_REDIRECTS) throw new ImageFetchException("unreachable", "Too many redirects");
                        var location = response.getFirstHeader("location");
                        if (location == null) throw new ImageFetchException("unreachable", "Redirect without location");
                        uri = parseHttp(uri.resolve(location.getValue()).toString(), deadline);
                        continue;
                    }
                    if (code != 200) throw new ImageFetchException("unreachable", "Photo source did not return an image");
                    var header = response.getFirstHeader("content-type");
                    String contentType = header == null ? "" : header.getValue().split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
                    if (!IMAGE_TYPES.contains(contentType)) throw new ImageFetchException("not_an_image", "Photo source is not an image");
                    if (response.getEntity() == null) throw new ImageFetchException("not_an_image", "Photo source is empty");
                    if (response.getEntity().getContentLength() > maxBytes) throw new ImageFetchException("too_large", "Photo exceeds the size limit");
                    return new FetchedImage(readBounded(response.getEntity().getContent()), contentType);
                } catch (ImageFetchException ex) {
                    if (request.isCancelled()) throw new ImageFetchException("timeout", "Photo fetching timed out");
                    throw ex;
                } catch (Exception ex) {
                    if (System.nanoTime() >= deadline || request.isCancelled()) throw new ImageFetchException("timeout", "Photo fetching timed out");
                    throw new ImageFetchException("unreachable", "Could not fetch the photo");
                } finally { cancellation.cancel(false); }
            }
        } catch (ImageFetchException ex) { throw ex; }
        catch (Exception ex) { throw new ImageFetchException("unreachable", "Could not fetch the photo"); }
    }

    private URI parseHttp(String rawUrl, long deadline) {
        URI uri;
        try { uri = URI.create(rawUrl.trim()); } catch (RuntimeException e) {
            throw new ImageFetchException("invalid_url", "malformed url");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new ImageFetchException("invalid_url", "only http(s) urls are allowed");
        }
        if (uri.getRawUserInfo() != null) throw new ImageFetchException("invalid_url", "URL credentials are not allowed");
        if (!allowLoopbackForTest && (!"https".equals(scheme) || (uri.getPort() != -1 && uri.getPort() != 443))) {
            validatedAddresses(uri.getHost(), deadline);
            throw new ImageFetchException("invalid_url", "Photo URLs require HTTPS on port 443");
        }
        return uri;
    }

    private InetAddress[] validatedAddresses(String host, long deadline) {
        InetAddress[] addrs;
        java.util.concurrent.Future<InetAddress[]> lookup = null;
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
            lookup = DNS_LOOKUPS.submit(() -> InetAddress.getAllByName(host));
            addrs = lookup.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new ImageFetchException("timeout", "Photo source resolution timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageFetchException("unreachable", "Photo fetch cancelled");
        } catch (Exception e) {
            throw new ImageFetchException("unreachable", "cannot resolve host");
        } finally {
            if (lookup != null) lookup.cancel(true);
        }
        for (InetAddress addr : addrs) {
            if (isBlockedAddress(addr)) {
                if (allowLoopbackForTest && addr.isLoopbackAddress()) continue;
                throw new ImageFetchException("blocked_host", "host resolves to a blocked address");
            }
        }
        return addrs;
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
            throw new ImageFetchException("unreachable", "Could not read the photo");
        }
    }
}
