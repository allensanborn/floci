package io.github.hectorvent.floci.services.ec2.net;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Network;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

/**
 * Gives every VPC a real Docker network, so an EC2 instance's private address is an address
 * something can actually connect to rather than a number invented to look plausible.
 *
 * <h2>One network per VPC, not per subnet</h2>
 *
 * <p>A Docker network is an isolation domain: containers on one can reach each other, and
 * cannot reach containers on another. That is exactly a VPC. It is <em>not</em> a subnet —
 * subnets inside a VPC route to each other by default in AWS, so giving each subnet its own
 * Docker network would manufacture a partition that AWS does not have, and would break the
 * very common two-tier "app subnet talks to database subnet" topology in this corpus.
 *
 * <p>The per-subnet addressing that AWS gets from separate subnets is preserved anyway: the
 * network is created with the whole VPC CIDR as its IPAM pool, and each subnet allocates
 * static addresses from its own slice of that pool. A subnet CIDR is by definition inside its
 * VPC CIDR, so every such address is valid on the network. The result is one bridge per VPC
 * instead of one per subnet — the correct semantics and, incidentally, a third of the Linux
 * bridges.
 *
 * <h2>Declared CIDRs are used where they can be, substituted loudly where they cannot</h2>
 *
 * <p>The declared CIDR is used verbatim whenever it is usable, which for this corpus is
 * almost always. It is not usable when it is absent, malformed, outside RFC 1918, or already
 * claimed by another Docker network — including one of Floci's own, which is what a second
 * VPC declaring the same CIDR looks like (legal in AWS, impossible on one Docker daemon).
 * Then, and only then, an equivalent block is allocated out of a configured private pool and
 * the substitution is logged at WARN. It is never silent: a reported private IP that does not
 * mean what the caller declared is exactly the class of quiet lie this whole change exists to
 * remove, so it is either true or it is in the log.
 *
 * <h2>What this does not do</h2>
 *
 * <p>Security groups and network ACLs are untouched. Docker networks isolate between
 * networks; within one, every container can reach every other on every port. Nothing here
 * makes an assertion that traffic is <em>blocked</em> meaningful.
 */
@ApplicationScoped
public class VpcNetworkManager {

    private static final Logger LOG = Logger.getLogger(VpcNetworkManager.class);

    /** Marks a Docker network as a Floci VPC network, for discovery and orphan reconciliation. */
    public static final String LABEL_COMPONENT = "floci_component";
    public static final String COMPONENT_VALUE = "ec2-vpc";
    public static final String LABEL_VPC_ID = "floci_vpc_id";
    public static final String LABEL_VPC_REGION = "floci_vpc_region";
    /**
     * The API port of the Floci process that created the network. Several Floci instances
     * routinely share one Docker daemon, and reconciliation deletes things; scoping it by
     * owner is what stops one instance's startup from tearing down another's live networks.
     */
    public static final String LABEL_OWNER_PORT = "floci_vpc_owner_port";

    /**
     * Instance addresses start here inside each subnet slice. AWS reserves the first four
     * addresses of a subnet and Docker takes the first as the gateway, so starting at .10
     * clears both without arithmetic that has to stay in sync with either.
     */
    static final int FIRST_HOST_OFFSET = 10;

    private final EmulatorConfig config;
    private final DockerClient dockerClient;

    /** region::vpcId -> binding. Rebuilt from persisted VPCs at startup. */
    private final Map<String, VpcBinding> bindings = new ConcurrentHashMap<>();
    /** region::subnetId -> region::vpcId, so subnet-keyed calls need no VPC lookup. */
    private final Map<String, String> subnetOwner = new ConcurrentHashMap<>();

    private final Object planLock = new Object();

    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ec2-vpc-network-teardown");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public VpcNetworkManager(EmulatorConfig config, DockerClient dockerClient) {
        this.config = config;
        this.dockerClient = dockerClient;
    }

    @PreDestroy
    void shutdown() {
        retries.shutdownNow();
    }

    public boolean enabled() {
        return config.services().ec2().vpcNetworks().enabled() && !config.services().ec2().mock();
    }

    // ─── Declaration: CreateVpc / CreateSubnet ────────────────────────────────

    /**
     * Records the address plan for a VPC. Called from CreateVpc and from default-resource
     * seeding; the Docker network itself is only created when the first instance needs it
     * (see {@link #attach}), because most roots in this corpus create VPCs they never launch
     * anything into and a Linux bridge per unused VPC is pure daemon churn.
     */
    public void declareVpc(String region, String vpcId, String declaredCidr) {
        if (!enabled() || region == null || vpcId == null) {
            return;
        }
        synchronized (planLock) {
            // Deliberately not computeIfAbsent: planVpc reads the whole binding map to detect
            // collisions, and ConcurrentHashMap forbids that from inside a mapping function.
            String vpcKey = key(region, vpcId);
            if (!bindings.containsKey(vpcKey)) {
                bindings.put(vpcKey, planVpc(region, vpcId, declaredCidr));
            }
        }
    }

    /** Records the address plan for a subnet inside an already-declared VPC. */
    public void declareSubnet(String region, String vpcId, String subnetId, String declaredCidr) {
        if (!enabled() || region == null || vpcId == null || subnetId == null) {
            return;
        }
        synchronized (planLock) {
            VpcBinding vpc = bindings.get(key(region, vpcId));
            if (vpc == null) {
                return;
            }
            if (!vpc.subnets.containsKey(subnetId)) {
                vpc.subnets.put(subnetId, planSubnet(vpc, subnetId, declaredCidr));
            }
            subnetOwner.put(key(region, subnetId), key(region, vpcId));
        }
    }

    private VpcBinding planVpc(String region, String vpcId, String declaredCidr) {
        Optional<Cidr4> declared = Cidr4.parse(declaredCidr);
        String rejection = rejectionReason(vpcId, declared, declaredCidr, List.of());
        Cidr4 effective;
        if (rejection == null) {
            effective = declared.orElseThrow();
        }
        else {
            effective = allocateFromFallbackPool(vpcId, declared.map(Cidr4::prefix).orElse(null));
            if (effective == null) {
                LOG.warnv("VPC {0} in {1}: declared CIDR {2} is unusable ({3}) and the fallback pool {4} "
                                + "is exhausted. This VPC gets no Docker network; its instances keep "
                                + "synthesised private addresses that nothing can connect to.",
                        vpcId, region, String.valueOf(declaredCidr), rejection,
                        config.services().ec2().vpcNetworks().fallbackPool());
            }
            else {
                LOG.warnv("VPC {0} in {1}: declared CIDR {2} is unusable ({3}). Substituting {4} from the "
                                + "fallback pool — reported private IPs for this VPC will NOT match the "
                                + "declared CIDR.",
                        vpcId, region, String.valueOf(declaredCidr), rejection, effective);
            }
        }
        return new VpcBinding(region, vpcId, declared.orElse(null), effective, rejection != null,
                networkName(region, vpcId));
    }

    /**
     * @return null when the block can be used as declared, otherwise a human-readable reason
     *         it cannot — which goes verbatim into the substitution WARN.
     */
    private String rejectionReason(String vpcId, Optional<Cidr4> declared, String declaredText,
                                   Collection<Cidr4> extraTaken) {
        if (declared.isEmpty()) {
            return declaredText == null || declaredText.isBlank()
                    ? "no CIDR was declared" : "not a parseable IPv4 CIDR";
        }
        Cidr4 cidr = declared.get();
        if (!cidr.isRfc1918()) {
            return "outside RFC 1918 private space (10/8, 172.16/12, 192.168/16)";
        }
        Optional<String> clash = firstClash(vpcId, cidr, extraTaken);
        return clash.orElse(null);
    }

    private Optional<String> firstClash(String vpcId, Cidr4 candidate, Collection<Cidr4> extraTaken) {
        for (Cidr4 taken : extraTaken) {
            if (candidate.overlaps(taken)) {
                return Optional.of("overlaps an address range already in use (" + taken + ")");
            }
        }
        for (VpcBinding other : bindings.values()) {
            if (other.effective != null && candidate.overlaps(other.effective)) {
                return Optional.of("already used by VPC " + other.vpcId + " (" + other.effective
                        + ") — two VPCs may declare the same CIDR in AWS, but one Docker daemon "
                        + "cannot route two identical ranges");
            }
        }
        for (Cidr4 taken : dockerNetworkSubnets(vpcId)) {
            if (candidate.overlaps(taken)) {
                return Optional.of("overlaps an existing Docker network (" + taken + ")");
            }
        }
        return Optional.empty();
    }

    private SubnetBinding planSubnet(VpcBinding vpc, String subnetId, String declaredCidr) {
        Optional<Cidr4> declared = Cidr4.parse(declaredCidr);
        List<Cidr4> siblings = vpc.subnets.values().stream().map(s -> s.effective).filter(c -> c != null).toList();

        if (vpc.effective == null) {
            return new SubnetBinding(subnetId, declared.orElse(null), null, true);
        }
        // The common, quiet path: the VPC kept its declared CIDR and the subnet sits inside it.
        if (!vpc.substituted && declared.isPresent()
                && vpc.effective.contains(declared.get())
                && siblings.stream().noneMatch(s -> s.overlaps(declared.get()))) {
            return new SubnetBinding(subnetId, declared.get(), declared.get(), false);
        }

        int desiredPrefix = declared.map(Cidr4::prefix).orElse(24);
        Cidr4 slice = allocateSubBlock(vpc.effective, desiredPrefix, siblings);
        if (slice == null) {
            LOG.warnv("Subnet {0} in VPC {1}: no free /{2} slice remains inside {3}; instances in this "
                            + "subnet fall back to synthesised private addresses.",
                    subnetId, vpc.vpcId, String.valueOf(desiredPrefix), vpc.effective);
            return new SubnetBinding(subnetId, declared.orElse(null), null, true);
        }
        LOG.warnv("Subnet {0} in VPC {1}: declared CIDR {2} cannot be used ({3}). Substituting {4} — "
                        + "reported private IPs for this subnet will NOT match the declared CIDR.",
                subnetId, vpc.vpcId, String.valueOf(declaredCidr),
                vpc.substituted ? "its VPC's CIDR was itself substituted for " + vpc.effective
                        : "it does not sit inside the VPC range " + vpc.effective + " or collides with a sibling subnet",
                slice);
        return new SubnetBinding(subnetId, declared.orElse(null), slice, true);
    }

    /** First block of {@code desiredPrefix} inside {@code parent} that no member of {@code taken} overlaps. */
    static Cidr4 allocateSubBlock(Cidr4 parent, int desiredPrefix, Collection<Cidr4> taken) {
        int prefix = Math.max(desiredPrefix, parent.prefix());
        Cidr4 candidate = new Cidr4(parent.network(), prefix);
        long blocks = 1L << (prefix - parent.prefix());
        for (long i = 0; i < blocks; i++) {
            Cidr4 block = candidate.shifted(i);
            boolean clash = taken.stream().anyMatch(block::overlaps);
            if (!clash) {
                return block;
            }
        }
        return null;
    }

    private Cidr4 allocateFromFallbackPool(String vpcId, Integer declaredPrefix) {
        EmulatorConfig.VpcNetworksConfig cfg = config.services().ec2().vpcNetworks();
        Optional<Cidr4> pool = Cidr4.parse(cfg.fallbackPool());
        if (pool.isEmpty() || !pool.get().isRfc1918()) {
            LOG.errorv("floci.services.ec2.vpc-networks.fallback-pool ({0}) is not a valid RFC 1918 CIDR; "
                    + "no substitution is possible.", cfg.fallbackPool());
            return null;
        }
        int prefix = cfg.fallbackPrefixLength();
        if (declaredPrefix != null && declaredPrefix > prefix) {
            // Never hand out a block smaller than what was asked for.
            prefix = Math.min(declaredPrefix, 30);
        }
        List<Cidr4> taken = new ArrayList<>(dockerNetworkSubnets(vpcId));
        bindings.values().stream().map(b -> b.effective).filter(c -> c != null).forEach(taken::add);
        return allocateSubBlock(pool.get(), prefix, taken);
    }

    // ─── Address allocation ──────────────────────────────────────────────────

    /**
     * The next free address inside the subnet's effective range.
     *
     * @return the address, or empty when the subnet has no Docker-backed range — in which case
     *         the caller keeps its existing synthesised address rather than reporting nothing.
     */
    public Optional<String> allocatePrivateIp(String region, String subnetId) {
        if (!enabled() || subnetId == null) {
            return Optional.empty();
        }
        SubnetBinding subnet = subnetBinding(region, subnetId);
        if (subnet == null || subnet.effective == null) {
            return Optional.empty();
        }
        synchronized (subnet) {
            long limit = subnet.effective.size() - 1;
            while (subnet.nextOffset < limit) {
                long offset = subnet.nextOffset++;
                Optional<String> address = subnet.effective.addressAt(offset);
                if (address.isPresent() && !subnet.leased.contains(address.get())) {
                    subnet.leased.add(address.get());
                    return address;
                }
            }
        }
        LOG.warnv("Subnet {0} range {1} is exhausted; further instances get no Docker-backed address.",
                subnetId, subnet.effective);
        return Optional.empty();
    }

    public void releasePrivateIp(String region, String subnetId, String address) {
        SubnetBinding subnet = subnetBinding(region, subnetId);
        if (subnet != null && address != null) {
            subnet.leased.remove(address);
        }
    }

    // ─── Materialisation: attaching a container ──────────────────────────────

    /**
     * Creates the VPC's Docker network if it does not exist yet, and attaches the container to
     * it at {@code address}.
     *
     * <p>The container keeps its default bridge attachment as well. That is deliberate: EC2
     * containers publish SSH on a host port, and on macOS Docker Desktop setting a network mode
     * alongside port bindings suppresses publishing entirely (see ContainerLifecycleManager).
     * Attaching as a second network keeps published ports working while making the VPC address
     * the one Floci reports, and the one instances in the same VPC use to reach each other.
     *
     * @return the network name on success, empty when the instance stays on the bridge only
     */
    public Optional<String> attach(String region, String vpcId, String subnetId,
                                   String containerId, String address) {
        if (!enabled() || containerId == null || address == null) {
            return Optional.empty();
        }
        VpcBinding vpc = bindings.get(key(region, vpcId));
        if (vpc == null || vpc.effective == null) {
            return Optional.empty();
        }
        // Only attach an address this manager actually planned. A synthesised address from
        // Ec2Service's fallback would be outside the network's IPAM pool, and Docker would
        // reject it — noisily, and after the container is already up.
        SubnetBinding subnet = subnetBinding(region, subnetId);
        Optional<Cidr4> parsed = Cidr4.parse(address + "/32");
        if (subnet == null || subnet.effective == null || parsed.isEmpty()
                || !subnet.effective.containsAddress(parsed.get().network())) {
            return Optional.empty();
        }
        String networkName = materialise(vpc);
        if (networkName == null) {
            return Optional.empty();
        }
        try {
            dockerClient.connectToNetworkCmd()
                    .withContainerId(containerId)
                    .withNetworkId(networkName)
                    .withContainerNetwork(new ContainerNetwork()
                            .withIpamConfig(new ContainerNetwork.Ipam().withIpv4Address(address)))
                    .exec();
            LOG.infov("Attached container {0} to VPC network {1} at {2}", containerId, networkName, address);
            return Optional.of(networkName);
        } catch (Exception e) {
            LOG.warnv("Could not attach container {0} to VPC network {1} at {2}: {3}. The instance keeps "
                            + "its bridge address and its reported private IP will not be reachable.",
                    containerId, networkName, address, e.getMessage());
            return Optional.empty();
        }
    }

    /** @return the network name, or null when creation failed */
    private String materialise(VpcBinding vpc) {
        synchronized (vpc) {
            if (vpc.created) {
                return vpc.networkName;
            }
            try {
                dockerClient.inspectNetworkCmd().withNetworkId(vpc.networkName).exec();
                vpc.created = true;
                return vpc.networkName;
            } catch (NotFoundException notThereYet) {
                // expected: first instance in this VPC
            } catch (Exception e) {
                LOG.debugv("Could not inspect VPC network {0}: {1}", vpc.networkName, e.getMessage());
            }
            try {
                dockerClient.createNetworkCmd()
                        .withName(vpc.networkName)
                        .withDriver(config.services().ec2().vpcNetworks().driver())
                        .withIpam(new Network.Ipam().withConfig(
                                new Network.Ipam.Config().withSubnet(vpc.effective.toString())))
                        .withLabels(networkLabels(vpc))
                        .exec();
                vpc.created = true;
                LOG.infov("Created Docker network {0} for VPC {1} ({2}){3}",
                        vpc.networkName, vpc.vpcId, vpc.effective,
                        vpc.substituted ? " [substituted; declared " + vpc.declared + "]" : "");
                return vpc.networkName;
            } catch (Exception e) {
                LOG.warnv("Could not create Docker network {0} for VPC {1} ({2}): {3}. Instances in this VPC "
                                + "stay on the shared bridge and are neither isolated nor addressed from the "
                                + "declared CIDR.",
                        vpc.networkName, vpc.vpcId, vpc.effective, e.getMessage());
                return null;
            }
        }
    }

    private Map<String, String> networkLabels(VpcBinding vpc) {
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.defaultLabels(config));
        labels.put(LABEL_COMPONENT, COMPONENT_VALUE);
        labels.put(LABEL_VPC_ID, vpc.vpcId);
        labels.put(LABEL_VPC_REGION, vpc.region);
        labels.put(LABEL_OWNER_PORT, String.valueOf(config.port()));
        return labels;
    }

    // ─── Teardown ────────────────────────────────────────────────────────────

    public void forgetSubnet(String region, String subnetId) {
        String vpcKey = subnetOwner.remove(key(region, subnetId));
        if (vpcKey == null) {
            return;
        }
        VpcBinding vpc = bindings.get(vpcKey);
        if (vpc != null) {
            vpc.subnets.remove(subnetId);
        }
    }

    /** Drops the VPC's plan and removes its Docker network, retrying while endpoints drain. */
    public void deleteVpcNetwork(String region, String vpcId) {
        VpcBinding vpc = bindings.remove(key(region, vpcId));
        if (vpc == null) {
            return;
        }
        vpc.subnets.keySet().forEach(subnetId -> subnetOwner.remove(key(region, subnetId)));
        if (!vpc.created) {
            return;
        }
        removeNetworkWithRetry(vpc.networkName, 1);
    }

    private void removeNetworkWithRetry(String networkName, int attempt) {
        try {
            dockerClient.removeNetworkCmd(networkName).exec();
            LOG.infov("Removed Docker network {0}", networkName);
        } catch (NotFoundException alreadyGone) {
            // nothing to do
        } catch (Exception e) {
            // Containers terminated moments earlier still hold endpoints for a beat.
            if (attempt < 5 && !retries.isShutdown()) {
                retries.schedule(() -> removeNetworkWithRetry(networkName, attempt + 1), 2, TimeUnit.SECONDS);
                return;
            }
            LOG.warnv("Could not remove Docker network {0} after {1} attempts: {2}. It will be reconciled "
                    + "at the next startup.", networkName, String.valueOf(attempt), e.getMessage());
        }
    }

    // ─── Startup reconciliation ──────────────────────────────────────────────

    /**
     * Removes VPC networks this Floci left behind on a previous run.
     *
     * <p>A crashed or SIGKILLed run leaves its bridges and its IPAM reservations on the daemon,
     * and the next run's identical CIDRs then collide with its own corpses — a repeat failure
     * mode in this project, and one that looks like a genuine collision, so it would silently
     * push every VPC into the fallback pool.
     *
     * <p>Only networks labelled with this process's own API port are considered. Several Floci
     * instances share a daemon here; scoping by owner is what makes the deletion safe.
     *
     * @param stillDeclared answers whether (region, vpcId) exists in the restored state
     */
    public void reconcileOrphans(BiPredicate<String, String> stillDeclared) {
        if (!enabled() || !config.services().ec2().vpcNetworks().reconcileOnStartup()) {
            return;
        }
        String owner = String.valueOf(config.port());
        int removed = 0;
        try {
            for (Network network : dockerClient.listNetworksCmd()
                    .withFilter("label", List.of(LABEL_COMPONENT + "=" + COMPONENT_VALUE))
                    .exec()) {
                Map<String, String> labels = network.getLabels() == null ? Map.of() : network.getLabels();
                if (!owner.equals(labels.get(LABEL_OWNER_PORT))) {
                    continue;
                }
                String vpcId = labels.get(LABEL_VPC_ID);
                String region = labels.get(LABEL_VPC_REGION);
                if (vpcId != null && region != null && stillDeclared.test(region, vpcId)) {
                    continue;
                }
                disconnectAll(network);
                try {
                    dockerClient.removeNetworkCmd(network.getId()).exec();
                    removed++;
                    LOG.infov("Reconciled orphaned VPC network {0} (vpc {1}) left by a previous run",
                            network.getName(), String.valueOf(vpcId));
                } catch (Exception e) {
                    LOG.warnv("Could not remove orphaned VPC network {0}: {1}", network.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not reconcile orphaned VPC networks: {0}", e.getMessage());
        }
        if (removed > 0) {
            LOG.infov("Removed {0} orphaned VPC network(s)", String.valueOf(removed));
        }
    }

    private void disconnectAll(Network network) {
        Map<String, Network.ContainerNetworkConfig> containers = network.getContainers();
        if (containers == null) {
            return;
        }
        for (String containerId : containers.keySet()) {
            try {
                dockerClient.disconnectFromNetworkCmd()
                        .withNetworkId(network.getId())
                        .withContainerId(containerId)
                        .withForce(true)
                        .exec();
            } catch (Exception e) {
                LOG.debugv("Could not disconnect {0} from {1}: {2}", containerId, network.getName(), e.getMessage());
            }
        }
    }

    // ─── Introspection, for tests and reporting ──────────────────────────────

    public Optional<String> effectiveVpcCidr(String region, String vpcId) {
        VpcBinding vpc = bindings.get(key(region, vpcId));
        return vpc == null || vpc.effective == null ? Optional.empty() : Optional.of(vpc.effective.toString());
    }

    public Optional<String> effectiveSubnetCidr(String region, String subnetId) {
        SubnetBinding subnet = subnetBinding(region, subnetId);
        return subnet == null || subnet.effective == null
                ? Optional.empty() : Optional.of(subnet.effective.toString());
    }

    public Optional<String> networkNameFor(String region, String vpcId) {
        VpcBinding vpc = bindings.get(key(region, vpcId));
        return vpc == null ? Optional.empty() : Optional.of(vpc.networkName);
    }

    public boolean isSubstituted(String region, String vpcId) {
        VpcBinding vpc = bindings.get(key(region, vpcId));
        return vpc != null && vpc.substituted;
    }

    String networkName(String region, String vpcId) {
        return ContainerStorageHelper.dockerName(config,
                "floci-vpc-" + config.port() + "-" + region + "-" + vpcId);
    }

    private SubnetBinding subnetBinding(String region, String subnetId) {
        String vpcKey = subnetOwner.get(key(region, subnetId));
        if (vpcKey == null) {
            return null;
        }
        VpcBinding vpc = bindings.get(vpcKey);
        return vpc == null ? null : vpc.subnets.get(subnetId);
    }

    /**
     * Every IPv4 range the daemon has already reserved, so a declared CIDR can be checked before use.
     *
     * @param ownVpcId the VPC being planned; this instance's surviving network for that same VPC is
     *                 excluded, so a Floci restart re-planning a VPC whose bridge is still up does
     *                 not read its own network as a collision and substitute a CIDR it already has
     */
    private Set<Cidr4> dockerNetworkSubnets(String ownVpcId) {
        Set<Cidr4> taken = new java.util.LinkedHashSet<>();
        String owner = String.valueOf(config.port());
        try {
            for (Network network : dockerClient.listNetworksCmd().exec()) {
                Map<String, String> labels = network.getLabels() == null ? Map.of() : network.getLabels();
                if (ownVpcId != null && ownVpcId.equals(labels.get(LABEL_VPC_ID))
                        && owner.equals(labels.get(LABEL_OWNER_PORT))) {
                    continue;
                }
                if (network.getIpam() == null || network.getIpam().getConfig() == null) {
                    continue;
                }
                for (Network.Ipam.Config ipam : network.getIpam().getConfig()) {
                    Cidr4.parse(ipam.getSubnet()).ifPresent(taken::add);
                }
            }
        } catch (Exception e) {
            // A daemon that cannot be listed cannot be collided with either; proceeding with the
            // declared CIDR is the honest choice, and createNetwork still refuses a real overlap.
            LOG.debugv("Could not list Docker networks for CIDR collision check: {0}", e.getMessage());
        }
        return taken;
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    private static final class VpcBinding {
        final String region;
        final String vpcId;
        final Cidr4 declared;
        final Cidr4 effective;
        final boolean substituted;
        final String networkName;
        final Map<String, SubnetBinding> subnets = new ConcurrentHashMap<>();
        volatile boolean created;

        VpcBinding(String region, String vpcId, Cidr4 declared, Cidr4 effective,
                   boolean substituted, String networkName) {
            this.region = region;
            this.vpcId = vpcId;
            this.declared = declared;
            this.effective = effective;
            this.substituted = substituted;
            this.networkName = networkName;
        }
    }

    private static final class SubnetBinding {
        final String subnetId;
        final Cidr4 declared;
        final Cidr4 effective;
        final boolean substituted;
        final Set<String> leased = ConcurrentHashMap.newKeySet();
        long nextOffset = FIRST_HOST_OFFSET;

        SubnetBinding(String subnetId, Cidr4 declared, Cidr4 effective, boolean substituted) {
            this.subnetId = subnetId;
            this.declared = declared;
            this.effective = effective;
            this.substituted = substituted;
        }
    }
}
