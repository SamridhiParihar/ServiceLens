package com.servicelens.ingestion;

import com.servicelens.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IngestionStrategyResolver}.
 *
 * <p>Verifies the three-way decision logic:
 * <ul>
 *   <li>Service not registered → {@link IngestionStrategy#FRESH}</li>
 *   <li>Service registered, force=false → {@link IngestionStrategy#INCREMENTAL}</li>
 *   <li>Service registered, force=true  → {@link IngestionStrategy#FORCE_FULL}</li>
 * </ul>
 */
@DisplayName("IngestionStrategyResolver")
@ExtendWith(MockitoExtension.class)
class IngestionStrategyResolverTest {

    @Mock
    private ServiceRegistryService registryService;

    private IngestionStrategyResolver resolver;

    private static final String SERVICE = "payment-service";

    @BeforeEach
    void setUp() {
        resolver = new IngestionStrategyResolver(registryService);
    }

    @Test
    @DisplayName("Returns FRESH when service is not registered")
    void returnsFreshWhenNotRegistered() {
        when(registryService.isRegistered(SERVICE)).thenReturn(false);

        assertThat(resolver.resolve(SERVICE, false)).isEqualTo(IngestionStrategy.FRESH);
    }

    @Test
    @DisplayName("Returns FRESH even when force=true if service is not registered")
    void returnsFreshForUnregisteredEvenWithForce() {
        when(registryService.isRegistered(SERVICE)).thenReturn(false);

        assertThat(resolver.resolve(SERVICE, true)).isEqualTo(IngestionStrategy.FRESH);
    }

    @Test
    @DisplayName("Returns INCREMENTAL when service is registered and force=false")
    void returnsIncrementalWhenRegisteredAndNoForce() {
        when(registryService.isRegistered(SERVICE)).thenReturn(true);

        assertThat(resolver.resolve(SERVICE, false)).isEqualTo(IngestionStrategy.INCREMENTAL);
    }

    @Test
    @DisplayName("Returns FORCE_FULL when service is registered and force=true")
    void returnsForceFullWhenRegisteredAndForced() {
        when(registryService.isRegistered(SERVICE)).thenReturn(true);

        assertThat(resolver.resolve(SERVICE, true)).isEqualTo(IngestionStrategy.FORCE_FULL);
    }
}
