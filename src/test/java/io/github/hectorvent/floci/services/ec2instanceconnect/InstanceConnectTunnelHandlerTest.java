package io.github.hectorvent.floci.services.ec2instanceconnect;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceConnectEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstanceConnectTunnelHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:30Z");
    private static final String EICE = "eice-0123456789abcdef0";

    private Ec2Service ec2;
    private InstanceConnectTunnelHandler handler;
    private Instance instance;
    private InstanceConnectEndpoint endpoint;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Service.class);
        handler = new InstanceConnectTunnelHandler(ec2, null, Clock.fixed(NOW, ZoneOffset.UTC));
        endpoint = new InstanceConnectEndpoint();
        endpoint.setInstanceConnectEndpointId(EICE);
        endpoint.setVpcId("vpc-1");
        instance = new Instance();
        instance.setInstanceId("i-0123456789abcdef0");
        instance.setPrivateIpAddress("10.100.1.4");
        instance.setContainerBridgeIp("192.168.97.5");
        when(ec2.findUsableInstanceConnectEndpoint(anyString(), anyString())).thenReturn(Optional.empty());
        when(ec2.findUsableInstanceConnectEndpoint("us-east-1", EICE)).thenReturn(Optional.of(endpoint));
        when(ec2.findRunningInstanceByPrivateIp(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(ec2.findRunningInstanceByPrivateIp("us-east-1", "vpc-1", "10.100.1.4")).thenReturn(Optional.of(instance));
        when(ec2.instanceConnectEndpointMayReach(eq(endpoint), eq(instance), anyInt())).thenReturn(true);
    }

    @Test
    void resolvesAValidRequestToTheInstancesReachableAddress() {
        InstanceConnectTunnelHandler.Target target = handler.resolve(query(Map.of())::get);

        assertEquals("192.168.97.5", target.host());
        assertEquals(22, target.port());
        assertEquals(3600, target.maxSeconds(), "AWS's limit is the default");
    }

    @Test
    void refusesWhatAwsRefuses() {
        assertEquals(403, refusal(Map.of("X-Amz-Credential", "AKID/20261016/us-east-1/ec2/aws4_request")));
        assertEquals(403, refusal(Map.of("X-Amz-Date", "20261016T115900Z")), "signed 90 s ago, valid 60 s");
        assertEquals(400, refusal(Map.of("remotePort", "6443")));
        assertEquals(400, refusal(Map.of("maxTunnelDuration", "3601")));
        assertEquals(404, refusal(Map.of("instanceConnectEndpointId", "eice-0000000000000000f")));
        assertEquals(404, refusal(Map.of("privateIpAddress", "10.100.1.99")));
        when(ec2.instanceConnectEndpointMayReach(eq(endpoint), eq(instance), anyInt())).thenReturn(false);
        assertEquals(403, refusal(Map.of()), "security groups deny");
    }

    private int refusal(Map<String, String> overrides) {
        return assertThrows(InstanceConnectTunnelHandler.Refusal.class,
                () -> handler.resolve(query(overrides)::get)).status;
    }

    private static Map<String, String> query(Map<String, String> overrides) {
        Map<String, String> query = new HashMap<>();
        query.put("X-Amz-Credential", "AKID/20261016/us-east-1/ec2-instance-connect/aws4_request");
        query.put("X-Amz-Date", "20261016T120000Z");
        query.put("X-Amz-Expires", "60");
        query.put("instanceConnectEndpointId", EICE);
        query.put("privateIpAddress", "10.100.1.4");
        query.put("remotePort", "22");
        query.putAll(overrides);
        return query;
    }
}
