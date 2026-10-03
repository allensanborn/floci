package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Launched containers talk to the Floci process directly, so the port must be the one Floci
 * listens on ({@code floci.port}), not the host-facing port advertised in {@code floci.base-url}.
 * With {@code docker run -p 4811:4566 -e FLOCI_BASE_URL=http://localhost:4811}, port 4811 exists
 * only on the host; inside the Docker network Floci answers on 4566.
 */
class ContainerReachableEndpointTest {

    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
    private final EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
    private final ContainerReachableEndpoint endpoint =
            new ContainerReachableEndpoint(config, dockerHostResolver, embeddedDnsServer);

    private void hostMappedTo4811() {
        when(config.port()).thenReturn(4566);
        lenient().when(config.baseUrl()).thenReturn("http://localhost:4811");
        lenient().when(config.hostname()).thenReturn(Optional.empty());
    }

    @Test
    void embeddedDnsUsesListenPortNotAdvertisedPort() {
        hostMappedTo4811();
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));

        assertEquals("http://" + EmbeddedDnsServer.DEFAULT_SUFFIX + ":4566", endpoint.baseUrl());
    }

    @Test
    void dockerHostFallbackUsesListenPortNotAdvertisedPort() {
        hostMappedTo4811();
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());
        when(dockerHostResolver.resolve()).thenReturn("172.18.0.2");

        assertEquals("http://172.18.0.2:4566", endpoint.baseUrl());
    }

    @Test
    void customListenPortIsHonoured() {
        when(config.port()).thenReturn(4811);
        lenient().when(config.baseUrl()).thenReturn("http://localhost:4811");
        when(config.hostname()).thenReturn(Optional.of("floci"));
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));

        assertEquals("http://floci:4811", endpoint.baseUrl());
    }
}
