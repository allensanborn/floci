package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Terraform's aws_instance sends its security groups on the primary interface spec
 * ({@code NetworkInterface.1.SecurityGroupId.N}) whenever private_ip or associate_public_ip_address is
 * set. RunInstances read only the top-level {@code SecurityGroupId.N}, so the instance landed in the
 * VPC's default group.
 */
class Ec2RunInstancesInterfaceSecurityGroupsTest {

    private static final String REGION = "us-east-1";

    @Test
    void groupsOnThePrimaryInterfaceSpecReachTheService() {
        MultivaluedMap<String, String> p = baseParams();
        p.putSingle("NetworkInterface.1.SecurityGroupId.1", "sg-0123456789abcdef0");
        p.putSingle("NetworkInterface.1.DeviceIndex", "0");
        assertEquals(List.of("sg-0123456789abcdef0"), forwardedGroups(p));
    }

    @Test
    void aSecondaryInterfaceSpecsGroupsAreNotTheInstances() {
        MultivaluedMap<String, String> p = baseParams();
        p.putSingle("NetworkInterface.1.SecurityGroupId.1", "sg-0123456789abcdef0");
        p.putSingle("NetworkInterface.1.DeviceIndex", "1");
        assertEquals(List.of(), forwardedGroups(p));
    }

    @Test
    void topLevelAndInterfaceGroupsTogetherAreRefused() {
        MultivaluedMap<String, String> p = baseParams();
        p.putSingle("NetworkInterface.1.SecurityGroupId.1", "sg-0123456789abcdef0");
        p.putSingle("SecurityGroupId.1", "sg-0fedcba9876543210");
        Response response = handler(mock(Ec2Service.class)).handle("RunInstances", p, REGION);
        assertEquals(400, response.getStatus());
        assertEquals(true, String.valueOf(response.getEntity()).contains("InvalidParameterCombination"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> forwardedGroups(MultivaluedMap<String, String> p) {
        Ec2Service service = mock(Ec2Service.class);
        when(service.runInstances(anyString(), nullable(String.class), nullable(String.class),
                anyInt(), anyInt(), nullable(String.class), anyList(), nullable(String.class),
                nullable(String.class), anyList(), nullable(String.class), nullable(String.class),
                nullable(Boolean.class), nullable(String.class), anyInt(), nullable(String.class),
                nullable(LaunchTemplateData.MetadataOptions.class), nullable(String.class), nullable(String.class), eq(false),
                nullable(String.class), nullable(String.class), nullable(List.class), eq(false), nullable(String.class)))
                .thenReturn(new Reservation());
        handler(service).handle("RunInstances", p, REGION);

        ArgumentCaptor<List<String>> groups = ArgumentCaptor.forClass(List.class);
        verify(service).runInstances(anyString(), nullable(String.class), nullable(String.class),
                anyInt(), anyInt(), nullable(String.class), groups.capture(), nullable(String.class),
                nullable(String.class), anyList(), nullable(String.class), nullable(String.class),
                nullable(Boolean.class), nullable(String.class), anyInt(), nullable(String.class),
                nullable(LaunchTemplateData.MetadataOptions.class), nullable(String.class), nullable(String.class), eq(false),
                nullable(String.class), nullable(String.class), nullable(List.class), eq(false), nullable(String.class));
        return groups.getValue();
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
}
