package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Terraform's aws_security_group revokes AWS's default allow-all egress rule as {@code -1} with
 * {@code FromPort=0, ToPort=0}. The revoke compared ports literally, never matched the stored rule
 * (which has none), and every new group kept allowing all egress.
 */
class Ec2RevokeAllTrafficTest {

    private static final String REGION = "us-east-1";

    @Test
    void revokingAllTrafficIgnoresThePortsTheRequestNames() {
        Ec2Service service = newService();
        String vpcId = service.createVpc(REGION, "10.74.0.0/16", false).getVpcId();
        String groupId = service.createSecurityGroup(REGION, "tf", "x", vpcId).getGroupId();

        service.revokeSecurityGroupEgress(REGION, groupId, List.of(permission("-1", 0, 0)));

        assertTrue(service.describeSecurityGroups(REGION, List.of(groupId), List.of(), Map.of())
                .getFirst().getIpPermissionsEgress().isEmpty(), "the default allow-all egress is gone");
    }

    @Test
    void portsStillMatterForOtherProtocols() {
        Ec2Service service = newService();
        String vpcId = service.createVpc(REGION, "10.76.0.0/16", false).getVpcId();
        String groupId = service.createSecurityGroup(REGION, "tf", "x", vpcId).getGroupId();
        service.authorizeSecurityGroupIngress(REGION, groupId, List.of(permission("tcp", 22, 22)));

        service.revokeSecurityGroupIngress(REGION, groupId, List.of(permission("tcp", 0, 0)));
        assertEquals(1, service.describeSecurityGroups(REGION, List.of(groupId), List.of(), Map.of())
                .getFirst().getIpPermissions().size(), "port 0 is not port 22");

        service.revokeSecurityGroupIngress(REGION, groupId, List.of(permission("6", 22, 22)));
        assertTrue(service.describeSecurityGroups(REGION, List.of(groupId), List.of(), Map.of())
                .getFirst().getIpPermissions().isEmpty(), "protocol 6 is tcp");
    }

    private static Ec2Service newService() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory("000000000000"));
    }

    private static IpPermission permission(String protocol, Integer from, Integer to) {
        IpPermission permission = new IpPermission();
        permission.setIpProtocol(protocol);
        permission.setFromPort(from);
        permission.setToPort(to);
        IpRange range = new IpRange();
        range.setCidrIp("0.0.0.0/0");
        permission.setIpRanges(new ArrayList<>(List.of(range)));
        return permission;
    }
}
