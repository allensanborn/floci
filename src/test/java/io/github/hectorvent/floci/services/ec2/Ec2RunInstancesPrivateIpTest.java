package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RunInstances honours the private IP a launch asks for (Terraform's {@code aws_instance.private_ip}),
 * either as the top-level {@code PrivateIpAddress} or on the primary interface spec, and validates it
 * as AWS does.
 */
class Ec2RunInstancesPrivateIpTest {

    private static final String REGION = "us-east-1";

    @Test
    void runInstancesHonoursARequestedPrivateIpAndValidatesItAsAwsDoes() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        Ec2Service service = new Ec2Service(config, mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory("000000000000"));
        String vpcId = service.createVpc(REGION, "10.70.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.70.1.0/24", "us-east-1a").getSubnetId();
        BiFunction<Integer, String, Reservation> launch = (count, ip) ->
                service.runInstances(REGION, "ami-1234567890abcdef0", "t3.micro", 1, count, null,
                        List.of(), subnetId, null, List.of(), null, null, null, null, 0, null, null, null,
                        null, false, null, null, null, false, ip);

        assertEquals("10.70.1.20", launch.apply(1, "10.70.1.20").getInstances().getFirst().getPrivateIpAddress());
        assertNotEquals("10.70.1.20", launch.apply(1, null).getInstances().getFirst().getPrivateIpAddress(),
                "an unrequested launch steps around the requested address");

        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("10.70.1.20", "InvalidIPAddress.InUse");
        refusals.put("10.70.2.20", "InvalidParameterValue");
        refusals.put("10.70.1.3", "InvalidParameterValue");
        refusals.put("10.70.1.255", "InvalidParameterValue");
        refusals.put("10.70.1.x", "InvalidParameterValue");
        refusals.forEach((ip, code) -> assertEquals(code,
                assertThrows(AwsException.class, () -> launch.apply(1, ip)).getErrorCode(), ip));
        assertEquals("InvalidParameterValue",
                assertThrows(AwsException.class, () -> launch.apply(2, "10.70.1.30")).getErrorCode());
        assertEquals("10.70.1.4", launch.apply(1, "10.70.1.4").getInstances().getFirst().getPrivateIpAddress(),
                "the first address AWS does not reserve");
    }

    @Test
    void requestedPrivateIpReachesTheServiceInEitherWireShape() {
        assertPrivateIpForwarded(params("PrivateIpAddress", "10.0.1.20"), "10.0.1.20");
        assertPrivateIpForwarded(params("NetworkInterface.1.PrivateIpAddress", "10.0.1.21"), "10.0.1.21");
        assertPrivateIpForwarded(baseParams(), null);
    }

    @Test
    void aSecondaryInterfaceSpecsAddressIsNotThePrimarys() {
        MultivaluedMap<String, String> p = params("NetworkInterface.1.PrivateIpAddress", "10.0.1.21");
        p.putSingle("NetworkInterface.1.DeviceIndex", "1");
        assertPrivateIpForwarded(p, null);
    }

    @Test
    void topLevelAndInterfaceAddressTogetherAreRefused() {
        MultivaluedMap<String, String> p = params("NetworkInterface.1.PrivateIpAddress", "10.0.1.21");
        p.putSingle("PrivateIpAddress", "10.0.1.20");
        Ec2Service service = mock(Ec2Service.class);
        Response response = handler(service).handle("RunInstances", p, REGION);
        assertEquals(400, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("InvalidParameterCombination"));
        verifyNoInteractions(service);
    }

    private void assertPrivateIpForwarded(MultivaluedMap<String, String> p, String expected) {
        Ec2Service service = mock(Ec2Service.class);
        when(service.runInstances(anyString(), nullable(String.class), nullable(String.class),
                anyInt(), anyInt(), nullable(String.class), anyList(), nullable(String.class),
                nullable(String.class), anyList(), nullable(String.class), nullable(String.class),
                nullable(Boolean.class), nullable(String.class), anyInt(), nullable(String.class),
                nullable(LaunchTemplateData.MetadataOptions.class), nullable(String.class), nullable(String.class), eq(false),
                nullable(String.class), nullable(String.class), nullable(List.class), eq(false), nullable(String.class)))
                .thenReturn(new Reservation());
        handler(service).handle("RunInstances", p, REGION);

        ArgumentCaptor<String> privateIp = ArgumentCaptor.forClass(String.class);
        verify(service).runInstances(anyString(), nullable(String.class), nullable(String.class),
                anyInt(), anyInt(), nullable(String.class), anyList(), nullable(String.class),
                nullable(String.class), anyList(), nullable(String.class), nullable(String.class),
                nullable(Boolean.class), nullable(String.class), anyInt(), nullable(String.class),
                nullable(LaunchTemplateData.MetadataOptions.class), nullable(String.class), nullable(String.class), eq(false),
                nullable(String.class), nullable(String.class), nullable(List.class), eq(false), privateIp.capture());
        assertEquals(expected, privateIp.getValue());
    }

    private static Ec2QueryHandler handler(Ec2Service service) {
        return new Ec2QueryHandler(service, mock(EmulatorConfig.class), mock(FlowLogService.class),
                mock(Ec2EbsEncryptionService.class), mock(Ec2SnapshotBlockPublicAccessService.class),
                mock(Ec2IpamService.class), mock(RegionResolver.class));
    }

    private static MultivaluedMap<String, String> baseParams() {
        MultivaluedMap<String, String> p = new MultivaluedHashMap<>();
        p.putSingle("ImageId", "ami-0abcdef1234567890");
        p.putSingle("InstanceType", "t3.micro");
        p.putSingle("MinCount", "1");
        p.putSingle("MaxCount", "1");
        return p;
    }

    private static MultivaluedMap<String, String> params(String key, String value) {
        MultivaluedMap<String, String> p = baseParams();
        p.putSingle(key, value);
        return p;
    }
}
