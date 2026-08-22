package io.github.hectorvent.floci.services.ec2.net;

import io.github.hectorvent.floci.config.EmulatorConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.CreateNetworkCmd;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.command.InspectNetworkCmd;
import com.github.dockerjava.api.command.ListNetworksCmd;
import com.github.dockerjava.api.command.RemoveNetworkCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Network;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VpcNetworkManagerTest {

    private static final String REGION = "us-east-1";

    private DockerClient docker;
    private EmulatorConfig config;
    private VpcNetworkManager manager;
    private List<Network> existingNetworks;
    private List<String> removedNetworks;

    @BeforeEach
    void setUp() {
        existingNetworks = new ArrayList<>();
        removedNetworks = new ArrayList<>();
        docker = mock(DockerClient.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4650);
        when(config.services().ec2().mock()).thenReturn(false);
        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(true);
        when(config.services().ec2().vpcNetworks().fallbackPool()).thenReturn("10.240.0.0/12");
        when(config.services().ec2().vpcNetworks().fallbackPrefixLength()).thenReturn(16);
        when(config.services().ec2().vpcNetworks().reconcileOnStartup()).thenReturn(true);
        when(config.services().ec2().vpcNetworks().driver()).thenReturn("bridge");
        when(config.docker().extraLabels()).thenReturn(List.of());
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());

        ListNetworksCmd list = mock(ListNetworksCmd.class, RETURNS_SELF);
        when(list.exec()).thenAnswer(invocation -> List.copyOf(existingNetworks));
        when(docker.listNetworksCmd()).thenReturn(list);

        InspectNetworkCmd inspect = mock(InspectNetworkCmd.class, RETURNS_SELF);
        when(inspect.exec()).thenThrow(new NotFoundException("no such network"));
        when(docker.inspectNetworkCmd()).thenReturn(inspect);

        CreateNetworkCmd create = mock(CreateNetworkCmd.class, RETURNS_SELF);
        when(create.exec()).thenReturn(new CreateNetworkResponse());
        when(docker.createNetworkCmd()).thenReturn(create);

        ConnectToNetworkCmd connect = mock(ConnectToNetworkCmd.class, RETURNS_SELF);
        when(docker.connectToNetworkCmd()).thenReturn(connect);

        when(docker.removeNetworkCmd(anyString())).thenAnswer(invocation -> {
            removedNetworks.add(invocation.getArgument(0));
            return mock(RemoveNetworkCmd.class, RETURNS_SELF);
        });

        manager = new VpcNetworkManager(config, docker);
    }

    private void existingNetwork(String name, String subnet, Map<String, String> labels) {
        Network network = mock(Network.class);
        when(network.getName()).thenReturn(name);
        when(network.getId()).thenReturn(name);
        when(network.getLabels()).thenReturn(labels);
        when(network.getIpam()).thenReturn(new Network.Ipam()
                .withConfig(new Network.Ipam.Config().withSubnet(subnet)));
        existingNetworks.add(network);
    }

    // ─── The ordinary case: the declared CIDR is what the instance gets ───────

    @Test
    void usesTheDeclaredCidrAndAllocatesInsideTheDeclaredSubnet() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");

        assertFalse(manager.isSubstituted(REGION, "vpc-1"));
        assertEquals("10.0.0.0/16", manager.effectiveVpcCidr(REGION, "vpc-1").orElseThrow());
        assertEquals("10.0.1.0/24", manager.effectiveSubnetCidr(REGION, "subnet-a").orElseThrow());

        Cidr4 declared = Cidr4.parse("10.0.1.0/24").orElseThrow();
        for (int i = 0; i < 5; i++) {
            String address = manager.allocatePrivateIp(REGION, "subnet-a").orElseThrow();
            long value = Cidr4.parse(address + "/32").orElseThrow().network();
            assertTrue(declared.containsAddress(value), address + " should be inside 10.0.1.0/24");
            assertNotEquals("10.0.1.0", address, "never the network address");
            assertNotEquals("10.0.1.1", address, "never the Docker gateway");
        }
    }

    @Test
    void handsOutADistinctAddressEachTime() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");
        String first = manager.allocatePrivateIp(REGION, "subnet-a").orElseThrow();
        String second = manager.allocatePrivateIp(REGION, "subnet-a").orElseThrow();
        assertNotEquals(first, second);
    }

    @Test
    void aReleasedAddressIsHandedOutAgainRatherThanExhaustingTheSubnet() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-tiny", "10.0.9.0/28");

        List<String> allocated = new ArrayList<>();
        Optional<String> next;
        while ((next = manager.allocatePrivateIp(REGION, "subnet-tiny")).isPresent()) {
            allocated.add(next.get());
        }
        assertTrue(allocated.size() >= 4, "a /28 should yield several usable addresses");
        assertTrue(manager.allocatePrivateIp(REGION, "subnet-tiny").isEmpty(), "and then be exhausted");

        manager.releasePrivateIp(REGION, "subnet-tiny", allocated.get(0));
        assertEquals(allocated.get(0), manager.allocatePrivateIp(REGION, "subnet-tiny").orElseThrow(),
                "a terminated instance's address must come back into circulation");
    }

    @Test
    void subnetsInTheSameVpcGetDisjointRangesOnOneNetwork() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");
        manager.declareSubnet(REGION, "vpc-1", "subnet-b", "10.0.2.0/24");

        assertEquals("10.0.1.0/24", manager.effectiveSubnetCidr(REGION, "subnet-a").orElseThrow());
        assertEquals("10.0.2.0/24", manager.effectiveSubnetCidr(REGION, "subnet-b").orElseThrow());
        // Same VPC, so the same Docker network: subnets inside a VPC route to each other in AWS.
        assertEquals(manager.networkNameFor(REGION, "vpc-1"), manager.networkNameFor(REGION, "vpc-1"));
    }

    // ─── The three substitution triggers ─────────────────────────────────────

    @Test
    void substitutesWhenTheDeclaredCidrIsOutsideRfc1918() {
        manager.declareVpc(REGION, "vpc-public", "54.0.0.0/16");
        assertTrue(manager.isSubstituted(REGION, "vpc-public"));
        Cidr4 effective = Cidr4.parse(manager.effectiveVpcCidr(REGION, "vpc-public").orElseThrow()).orElseThrow();
        assertTrue(effective.isRfc1918());
        assertTrue(Cidr4.parse("10.240.0.0/12").orElseThrow().contains(effective));
    }

    @Test
    void substitutesWhenNoCidrIsDeclared() {
        manager.declareVpc(REGION, "vpc-blank", null);
        assertTrue(manager.isSubstituted(REGION, "vpc-blank"));
        assertTrue(manager.effectiveVpcCidr(REGION, "vpc-blank").isPresent());
    }

    @Test
    void substitutesWhenTheDeclaredCidrCollidesWithAnExistingDockerNetwork() {
        // OrbStack's default bridge on this machine.
        existingNetwork("orbstack", "192.168.215.0/24", Map.of());

        manager.declareVpc(REGION, "vpc-clash", "192.168.215.0/24");

        assertTrue(manager.isSubstituted(REGION, "vpc-clash"));
        Cidr4 effective = Cidr4.parse(manager.effectiveVpcCidr(REGION, "vpc-clash").orElseThrow()).orElseThrow();
        assertFalse(effective.overlaps(Cidr4.parse("192.168.215.0/24").orElseThrow()));
    }

    @Test
    void twoVpcsMayDeclareTheSameCidrAndBothKeepWorking() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-1", "10.0.1.0/24");
        manager.declareVpc(REGION, "vpc-2", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-2", "subnet-2", "10.0.1.0/24");

        assertFalse(manager.isSubstituted(REGION, "vpc-1"), "the first claim keeps the declared range");
        assertTrue(manager.isSubstituted(REGION, "vpc-2"), "the second is substituted, not rejected");

        Cidr4 first = Cidr4.parse(manager.effectiveVpcCidr(REGION, "vpc-1").orElseThrow()).orElseThrow();
        Cidr4 second = Cidr4.parse(manager.effectiveVpcCidr(REGION, "vpc-2").orElseThrow()).orElseThrow();
        assertFalse(first.overlaps(second));

        // Both still allocate — a duplicate CIDR must not leave a VPC unusable.
        String a = manager.allocatePrivateIp(REGION, "subnet-1").orElseThrow();
        String b = manager.allocatePrivateIp(REGION, "subnet-2").orElseThrow();
        assertTrue(first.containsAddress(Cidr4.parse(a + "/32").orElseThrow().network()));
        assertTrue(second.containsAddress(Cidr4.parse(b + "/32").orElseThrow().network()));
    }

    @Test
    void aSubnetOutsideItsVpcRangeIsRemappedInsideIt() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-stray", "172.16.5.0/24");

        Cidr4 effective = Cidr4.parse(manager.effectiveSubnetCidr(REGION, "subnet-stray").orElseThrow()).orElseThrow();
        assertTrue(Cidr4.parse("10.0.0.0/16").orElseThrow().contains(effective),
                "an address must be routable on the VPC's own network to mean anything");
    }

    // ─── Materialisation and attachment ──────────────────────────────────────

    @Test
    void createsTheNetworkWithTheEffectiveCidrOnFirstAttach() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");
        String address = manager.allocatePrivateIp(REGION, "subnet-a").orElseThrow();

        assertTrue(manager.attach(REGION, "vpc-1", "subnet-a", "container-1", address).isPresent());

        ArgumentCaptor<Network.Ipam> ipam = ArgumentCaptor.forClass(Network.Ipam.class);
        verify(docker.createNetworkCmd()).withIpam(ipam.capture());
        assertEquals("10.0.0.0/16", ipam.getValue().getConfig().get(0).getSubnet());
        verify(docker.connectToNetworkCmd()).withContainerId("container-1");
    }

    @Test
    void refusesToAttachAnAddressItDidNotPlan() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");

        // The synthesised address Ec2Service falls back to: outside the network's IPAM pool.
        assertTrue(manager.attach(REGION, "vpc-1", "subnet-a", "container-1", "172.31.0.11").isEmpty());
        verify(docker, never()).connectToNetworkCmd();
    }

    @Test
    void doesNothingWhenDisabled() {
        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(false);
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");
        assertTrue(manager.allocatePrivateIp(REGION, "subnet-a").isEmpty());
        assertTrue(manager.effectiveVpcCidr(REGION, "vpc-1").isEmpty());
    }

    // ─── Startup reconciliation ──────────────────────────────────────────────

    @Test
    void reconcileRemovesOnlyThisEmulatorsOwnOrphans() {
        existingNetwork("floci-vpc-4650-us-east-1-vpc-dead", "10.7.0.0/16", ourLabels("vpc-dead", "4650"));
        existingNetwork("floci-vpc-4650-us-east-1-vpc-live", "10.8.0.0/16", ourLabels("vpc-live", "4650"));
        existingNetwork("floci-vpc-4620-us-east-1-vpc-other", "10.9.0.0/16", ourLabels("vpc-other", "4620"));
        existingNetwork("some-users-network", "10.10.0.0/16", Map.of());

        manager.reconcileOrphans((region, vpcId) -> "vpc-live".equals(vpcId));

        assertEquals(List.of("floci-vpc-4650-us-east-1-vpc-dead"), removedNetworks,
                "a live VPC keeps its network, another emulator's networks are never touched, "
                        + "and networks Floci did not create are out of scope entirely");
    }

    @Test
    void reconcileIsSkippedWhenTurnedOff() {
        when(config.services().ec2().vpcNetworks().reconcileOnStartup()).thenReturn(false);
        existingNetwork("floci-vpc-4650-us-east-1-vpc-dead", "10.7.0.0/16", ourLabels("vpc-dead", "4650"));
        manager.reconcileOrphans((region, vpcId) -> false);
        assertTrue(removedNetworks.isEmpty());
    }

    @Test
    void aSurvivingNetworkForTheSameVpcIsNotReadAsACollision() {
        // What a Floci restart sees: its own bridge for vpc-1 is still up.
        existingNetwork(manager.networkName(REGION, "vpc-1"), "10.0.0.0/16", ourLabels("vpc-1", "4650"));

        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");

        assertFalse(manager.isSubstituted(REGION, "vpc-1"),
                "re-planning a VPC must not collide with that VPC's own surviving network");
        assertEquals("10.0.0.0/16", manager.effectiveVpcCidr(REGION, "vpc-1").orElseThrow());
    }

    private static Map<String, String> ourLabels(String vpcId, String ownerPort) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(VpcNetworkManager.LABEL_COMPONENT, VpcNetworkManager.COMPONENT_VALUE);
        labels.put(VpcNetworkManager.LABEL_VPC_ID, vpcId);
        labels.put(VpcNetworkManager.LABEL_VPC_REGION, REGION);
        labels.put(VpcNetworkManager.LABEL_OWNER_PORT, ownerPort);
        return labels;
    }

    @Test
    void subBlockAllocationSkipsTakenRanges() {
        Cidr4 parent = Cidr4.parse("10.0.0.0/16").orElseThrow();
        Cidr4 taken = Cidr4.parse("10.0.0.0/24").orElseThrow();
        assertEquals("10.0.1.0/24",
                VpcNetworkManager.allocateSubBlock(parent, 24, List.of(taken)).toString());
        assertEquals(null,
                VpcNetworkManager.allocateSubBlock(parent, 16, List.of(parent)),
                "a fully claimed parent yields nothing rather than an overlapping block");
    }
}
