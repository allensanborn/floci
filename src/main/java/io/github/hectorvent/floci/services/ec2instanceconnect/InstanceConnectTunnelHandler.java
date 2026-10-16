package io.github.hectorvent.floci.services.ec2instanceconnect;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceConnectEndpoint;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The EC2 Instance Connect Endpoint data plane: {@code wss://<endpoint DnsName>/openTunnel}, the
 * SigV4-presigned WebSocket the AWS CLI opens for {@code aws ec2-instance-connect open-tunnel} and
 * {@code ssh --connection-type eice}. Each binary frame carries raw TCP bytes to and from the
 * instance's port, the same contract as AWS's endpoint.
 *
 * <p>Checked before the upgrade, each refusal answered as an HTTP error the CLI prints: the URL's
 * expiry, the endpoint (known, usable), the target (a running instance at that private address in
 * the endpoint's VPC), the port (22 or 3389, as AWS allows), and the security groups (the
 * endpoint's egress and the instance's ingress must both allow the port). The SigV4 signature
 * itself is not verified.
 */
@ApplicationScoped
public class InstanceConnectTunnelHandler {

    private static final Logger LOG = Logger.getLogger(InstanceConnectTunnelHandler.class);

    public static final String PATH = "/openTunnel";
    /** The ports an EC2 Instance Connect Endpoint forwards: SSH and RDP. */
    static final Set<Integer> ALLOWED_PORTS = Set.of(22, 3389);
    /** AWS's limit, and the CLI's default, for {@code maxTunnelDuration}. */
    static final int MAX_TUNNEL_SECONDS = 3600;
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    private final Ec2Service ec2Service;
    private final Vertx vertx;
    private final Clock clock;
    private NetClient netClient;

    @Inject
    public InstanceConnectTunnelHandler(Ec2Service ec2Service, Vertx vertx) {
        this(ec2Service, vertx, Clock.systemUTC());
    }

    InstanceConnectTunnelHandler(Ec2Service ec2Service, Vertx vertx, Clock clock) {
        this.ec2Service = ec2Service;
        this.vertx = vertx;
        this.clock = clock;
    }

    void init(@Observes Router router) {
        router.route(PATH).handler(this::open);
        LOG.debugv("Registered the EC2 Instance Connect Endpoint tunnel on {0}", PATH);
    }

    /** What a valid request resolved to. */
    record Target(InstanceConnectEndpoint endpoint, Instance instance, String host, int port, int maxSeconds) {}

    /** Thrown for a request the endpoint refuses; the status and message become the HTTP answer. */
    static final class Refusal extends RuntimeException {
        final int status;

        Refusal(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private void open(RoutingContext ctx) {
        String upgrade = ctx.request().getHeader("Upgrade");
        if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade)) {
            ctx.next();
            return;
        }
        Target target;
        try {
            target = resolve(ctx.queryParams()::get);
        } catch (Refusal refusal) {
            ctx.response().setStatusCode(refusal.status).end(refusal.getMessage());
            return;
        }
        // Connect first, so an unreachable instance is a clean HTTP error rather than a socket
        // that opens and immediately closes.
        ctx.request().pause();
        client().connect(target.port(), target.host()).onComplete(connected -> {
            if (connected.failed()) {
                ctx.request().resume();
                ctx.response().setStatusCode(504).end("Unable to reach " + target.instance().getPrivateIpAddress()
                        + ":" + target.port() + ": " + connected.cause().getMessage());
                return;
            }
            NetSocket socket = connected.result();
            ctx.request().resume();
            ctx.request().toWebSocket().onSuccess(webSocket -> {
                LOG.infov("EC2 Instance Connect Endpoint {0}: tunnel to {1} ({2}:{3}) opened",
                        target.endpoint().getInstanceConnectEndpointId(), target.instance().getInstanceId(),
                        target.instance().getPrivateIpAddress(), String.valueOf(target.port()));
                webSocket.pipeTo(socket);
                socket.pipeTo(webSocket);
                long timer = vertx.setTimer(target.maxSeconds() * 1000L, ignored -> webSocket.close());
                webSocket.closeHandler(ignored -> {
                    vertx.cancelTimer(timer);
                    socket.close();
                });
                socket.closeHandler(ignored -> {
                    if (!webSocket.isClosed()) {
                        webSocket.close();
                    }
                });
            }).onFailure(cause -> {
                socket.close();
                LOG.warnv("EC2 Instance Connect Endpoint tunnel upgrade failed: {0}", cause.getMessage());
            });
        });
    }

    /** Validates a tunnel request's query parameters into its target. */
    Target resolve(Function<String, String> query) {
        String region = regionOf(query.apply("X-Amz-Credential"));
        checkNotExpired(query.apply("X-Amz-Date"), query.apply("X-Amz-Expires"));
        String endpointId = required(query, "instanceConnectEndpointId");
        String privateIp = required(query, "privateIpAddress");
        int port = integer(query, "remotePort", null);
        if (!ALLOWED_PORTS.contains(port)) {
            throw new Refusal(400, "remotePort " + port + " is not supported; an EC2 Instance Connect Endpoint "
                    + "forwards ports 22 and 3389");
        }
        int maxSeconds = integer(query, "maxTunnelDuration", MAX_TUNNEL_SECONDS);
        if (maxSeconds < 1 || maxSeconds > MAX_TUNNEL_SECONDS) {
            throw new Refusal(400, "maxTunnelDuration must be from 1 to " + MAX_TUNNEL_SECONDS + " seconds");
        }
        InstanceConnectEndpoint endpoint = ec2Service.findUsableInstanceConnectEndpoint(region, endpointId)
                .orElseThrow(() -> new Refusal(404, "No available EC2 Instance Connect Endpoint " + endpointId
                        + " in " + region));
        Instance instance = ec2Service.findRunningInstanceByPrivateIp(region, endpoint.getVpcId(), privateIp)
                .orElseThrow(() -> new Refusal(404, "No running instance at " + privateIp + " in "
                        + endpoint.getVpcId()));
        if (!ec2Service.instanceConnectEndpointMayReach(endpoint, instance, port)) {
            throw new Refusal(403, "The security groups of " + endpointId + " and " + instance.getInstanceId()
                    + " do not allow TCP " + port + " from the endpoint to the instance");
        }
        String host = Optional.ofNullable(instance.getContainerBridgeIp()).filter(ip -> !ip.isBlank())
                .orElse(privateIp);
        return new Target(endpoint, instance, host, port, maxSeconds);
    }

    private static String regionOf(String credential) {
        String[] scope = credential == null ? new String[0] : credential.split("/");
        if (scope.length < 5 || !"ec2-instance-connect".equals(scope[3])) {
            throw new Refusal(403, "The request is not presigned for ec2-instance-connect");
        }
        return scope[2];
    }

    private void checkNotExpired(String date, String expires) {
        try {
            Instant signedAt = LocalDateTime.parse(date, AMZ_DATE).toInstant(ZoneOffset.UTC);
            if (clock.instant().isAfter(signedAt.plusSeconds(Long.parseLong(expires)))) {
                throw new Refusal(403, "Request has expired");
            }
        } catch (DateTimeParseException | NumberFormatException | NullPointerException e) {
            throw new Refusal(403, "The request is missing a valid X-Amz-Date or X-Amz-Expires");
        }
    }

    private static String required(Function<String, String> query, String name) {
        String value = query.apply(name);
        if (value == null || value.isBlank()) {
            throw new Refusal(400, name + " is required");
        }
        return value;
    }

    private static int integer(Function<String, String> query, String name, Integer fallback) {
        String value = query.apply(name);
        if (value == null || value.isBlank()) {
            if (fallback == null) {
                throw new Refusal(400, name + " is required");
            }
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new Refusal(400, name + " must be an integer");
        }
    }

    private synchronized NetClient client() {
        if (netClient == null) {
            netClient = vertx.createNetClient(new NetClientOptions().setConnectTimeout(CONNECT_TIMEOUT_MILLIS));
        }
        return netClient;
    }
}
