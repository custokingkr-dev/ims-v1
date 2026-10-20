package com.custoking.ims.schoolcoreservice.infrastructure;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class ImageUrlFetcherDnsBindingTest {
    @Test void rebindingIsRejectedByTheActualApacheConnectionResolver() throws Exception {
        var calls=new AtomicInteger();
        var publicAddress=InetAddress.getByName("8.8.8.8");var privateAddress=InetAddress.getByName("127.0.0.1");
        var client=ImageUrlFetcher.forTestUsingResolver(1024, host -> new InetAddress[]{calls.incrementAndGet()==1 ? publicAddress : privateAddress});
        assertThatThrownBy(() -> client.fetch("https://controlled.invalid/photo.png")).isInstanceOf(ImageFetchException.class);
        assertThat(calls.get()).isEqualTo(2); // Preflight public; native Apache DNS resolution private. No socket is allowed to connect.
    }
    @Test void mixedPublicPrivateAndEmbeddedIpv4AnswersAreRejectedWithoutConnecting() throws Exception {
        for(String blocked:new String[]{"10.0.0.1","169.254.169.254","64:ff9b::a00:1"}) {
            var publicAddress=InetAddress.getByName("8.8.8.8");var privateAddress=InetAddress.getByName(blocked);var calls=new AtomicInteger();
            var client=ImageUrlFetcher.forTestUsingResolver(1024,host->{calls.incrementAndGet();return new InetAddress[]{publicAddress,privateAddress};});
            assertThatThrownBy(()->client.fetch("https://controlled.invalid/photo.png")).isInstanceOf(ImageFetchException.class).extracting(e->((ImageFetchException)e).reason()).isEqualTo("blocked_host");
            assertThat(calls.get()).isEqualTo(1);
        }
    }
}
