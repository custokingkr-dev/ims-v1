package com.custoking.ims.identityservice;

import com.custoking.ims.identityservice.security.TenantContextFilter;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Existing controller authorization matrices start with a separately verified session. */
public final class VerifiedSessionFixtures {
    public static TenantContextFilter filter() {
        JdbcClient jdbc=mock(JdbcClient.class,RETURNS_DEEP_STUBS);
        when(jdbc.sql(anyString()).param(eq("session"),any()).param(eq("user"),any()).query(Integer.class).optional())
                .thenReturn(Optional.of(1));
        return new TenantContextFilter(jdbc);
    }
}
