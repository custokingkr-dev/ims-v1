package com.custoking.ims.architecture;

import com.custoking.ims.CustokingImsApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

class ModulithDiscoveryTest {

    @Test
    void springModulithCanDiscoverTheApplicationModules() {
        ApplicationModules modules = ApplicationModules.of(CustokingImsApplication.class);

        assertThat(modules).isNotNull();
        assertThat(modules.toString()).isNotBlank();
    }
}
