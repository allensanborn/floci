package io.floci.aws.services.amazonmq;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.services.amazonmq.model.Broker;
import io.floci.aws.services.amazonmq.model.MqConfiguration;
import io.floci.aws.services.amazonmq.model.MqConfiguration.Revision;
import io.floci.aws.services.amazonmq.model.MqUser;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon MQ control plane (REST JSON). Paths and wire keys mirror the AWS
 * {@code mq} API (camelCase bodies under {@code /v1/brokers}).
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AmazonMqController {

    private final AmazonMqService service;
    private final AmazonMqConfigurationService configurations;

    @Inject
    public AmazonMqController(AmazonMqService service, AmazonMqConfigurationService configurations) {
        this.service = service;
        this.configurations = configurations;
    }

    // Configurations share /v1/configurations with MSK; AmazonMqRouteFilter rewrites a request
    // signed for mq onto this internal prefix.
    private static final String CONFIGURATIONS = AmazonMqRouteFilter.INTERNAL_PREFIX + "/v1/configurations";

    @POST
    @Path(CONFIGURATIONS)
    public Response createConfiguration(Map<String, Object> request) {
        MqConfiguration c = configurations.create(str(request, "name"), str(request, "engineType"),
                str(request, "engineVersion"), str(request, "authenticationStrategy"),
                tags(request.get("tags")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("arn", c.arn);
        putIfPresent(body, "authenticationStrategy", c.authenticationStrategy);
        body.put("created", c.created);
        body.put("id", c.id);
        body.put("latestRevision", revisionView(c.latest()));
        body.put("name", c.name);
        return Response.ok(body).build();
    }

    @GET
    @Path(CONFIGURATIONS)
    public Response listConfigurations(@QueryParam("maxResults") String maxResults,
                                       @QueryParam("nextToken") String nextToken) {
        Integer max = Pagination.parseMaxResults(maxResults, "BadRequestException");
        PaginatedResult<MqConfiguration> page = configurations.list(max, nextToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configurations", page.items().stream().map(AmazonMqController::configurationView).toList());
        body.put("maxResults", max != null ? max : AmazonMqConfigurationService.DEFAULT_PAGE);
        if (page.nextToken() != null) {
            body.put("nextToken", page.nextToken());
        }
        return Response.ok(body).build();
    }

    @GET
    @Path(CONFIGURATIONS + "/{configuration-id}")
    public Response describeConfiguration(@PathParam("configuration-id") String id) {
        return Response.ok(configurationView(configurations.describe(id))).build();
    }

    @PUT
    @Path(CONFIGURATIONS + "/{configuration-id}")
    public Response updateConfiguration(@PathParam("configuration-id") String id, Map<String, Object> request) {
        MqConfiguration c = configurations.update(id, str(request, "data"), str(request, "description"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("arn", c.arn);
        body.put("created", c.created);
        body.put("id", c.id);
        body.put("latestRevision", revisionView(c.latest()));
        body.put("name", c.name);
        body.put("warnings", List.of());
        return Response.ok(body).build();
    }

    @DELETE
    @Path(CONFIGURATIONS + "/{configuration-id}")
    public Response deleteConfiguration(@PathParam("configuration-id") String id) {
        configurations.delete(id);
        return Response.ok(Map.of("configurationId", id)).build();
    }

    @GET
    @Path(CONFIGURATIONS + "/{configuration-id}/revisions")
    public Response listConfigurationRevisions(@PathParam("configuration-id") String id,
                                               @QueryParam("maxResults") String maxResults,
                                               @QueryParam("nextToken") String nextToken) {
        Integer max = Pagination.parseMaxResults(maxResults, "BadRequestException");
        PaginatedResult<Revision> page = configurations.revisions(id, max, nextToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configurationId", id);
        body.put("maxResults", max != null ? max : AmazonMqConfigurationService.DEFAULT_PAGE);
        body.put("revisions", page.items().stream().map(AmazonMqController::revisionView).toList());
        if (page.nextToken() != null) {
            body.put("nextToken", page.nextToken());
        }
        return Response.ok(body).build();
    }

    @GET
    @Path(CONFIGURATIONS + "/{configuration-id}/revisions/{revision}")
    public Response describeConfigurationRevision(@PathParam("configuration-id") String id,
                                                  @PathParam("revision") String revision) {
        int number;
        try {
            number = Integer.parseInt(revision);
        } catch (NumberFormatException e) {
            throw new AwsException("BadRequestException", "Invalid revision: " + revision, 400);
        }
        Revision r = configurations.revision(id, number);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configurationId", id);
        body.put("created", r.created);
        body.put("data", r.data);
        body.put("description", r.description);
        return Response.ok(body).build();
    }

    @POST
    @Path("/v1/brokers")
    public Response createBroker(Map<String, Object> request) {
        CreateBrokerParams params = new CreateBrokerParams(
                str(request, "brokerName"),
                str(request, "engineType"),
                str(request, "engineVersion"),
                str(request, "deploymentMode"),
                str(request, "hostInstanceType"),
                bool(request, "publiclyAccessible"),
                bool(request, "autoMinorVersionUpgrade"),
                parseUsers(request.get("users")),
                tags(request.get("tags")),
                strList(request.get("securityGroups")),
                strList(request.get("subnetIds")),
                map(request.get("logs")),
                map(request.get("maintenanceWindowStartTime")),
                str(request, "storageType"),
                str(request, "authenticationStrategy"),
                map(request.get("encryptionOptions")),
                map(request.get("configuration")));
        Broker broker = service.createBroker(params);
        return Response.ok(Map.of(
                "brokerArn", broker.getBrokerArn(),
                "brokerId", broker.getBrokerId())).build();
    }

    @GET
    @Path("/v1/brokers")
    public Response listBrokers() {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Broker b : service.listBrokers()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("brokerArn", b.getBrokerArn());
            summary.put("brokerId", b.getBrokerId());
            summary.put("brokerName", b.getBrokerName());
            summary.put("brokerState", b.getBrokerState());
            summary.put("created", b.getCreated());
            summary.put("deploymentMode", b.getDeploymentMode());
            summary.put("engineType", b.getEngineType());
            summary.put("hostInstanceType", b.getHostInstanceType());
            summaries.add(summary);
        }
        return Response.ok(Map.of("brokerSummaries", summaries)).build();
    }

    @GET
    @Path("/v1/brokers/{broker-id}")
    public Response describeBroker(@PathParam("broker-id") String brokerId) {
        return Response.ok(brokerResponse(service.describeBroker(brokerId))).build();
    }

    // Builds the DescribeBroker response explicitly. The Broker model persists
    // internal bookkeeping (containerId, accountId, volumeId) so the broker can be
    // managed after a restart, but those fields are not part of the AWS shape — hand-
    // building the response keeps them out of the client-facing payload.
    private static Map<String, Object> brokerResponse(Broker b) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brokerId", b.getBrokerId());
        body.put("brokerArn", b.getBrokerArn());
        body.put("brokerName", b.getBrokerName());
        body.put("brokerState", b.getBrokerState());
        body.put("engineType", b.getEngineType());
        body.put("engineVersion", b.getEngineVersion());
        body.put("deploymentMode", b.getDeploymentMode());
        body.put("hostInstanceType", b.getHostInstanceType());
        body.put("publiclyAccessible", b.isPubliclyAccessible());
        body.put("autoMinorVersionUpgrade", b.isAutoMinorVersionUpgrade());
        body.put("created", b.getCreated());
        body.put("brokerInstances", b.getBrokerInstances());
        body.put("tags", b.getTags());
        // Optional CreateBroker members: omitted when the request did not set them.
        putIfPresent(body, "securityGroups", b.getSecurityGroups());
        putIfPresent(body, "subnetIds", b.getSubnetIds());
        body.put("logs", logsSummary(b));
        putIfPresent(body, "maintenanceWindowStartTime", b.getMaintenanceWindowStartTime());
        putIfPresent(body, "storageType", b.getStorageType());
        putIfPresent(body, "authenticationStrategy", b.getAuthenticationStrategy());
        putIfPresent(body, "encryptionOptions", b.getEncryptionOptions());
        // DescribeBroker reports the CreateBroker configuration as configurations.current.
        if (b.getConfiguration() != null) {
            body.put("configurations", Map.of("current", b.getConfiguration()));
        }
        return body;
    }

    // LogsSummary: general and generalLogGroup are required. Audit logs are ActiveMQ-only,
    // so a RabbitMQ broker reports the general log group alone.
    private static Map<String, Object> logsSummary(Broker b) {
        boolean general = b.getLogs() != null && Boolean.TRUE.equals(b.getLogs().get("general"));
        Map<String, Object> logs = new LinkedHashMap<>();
        logs.put("general", general);
        logs.put("generalLogGroup", b.generalLogGroup());
        return logs;
    }

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    @DELETE
    @Path("/v1/brokers/{broker-id}")
    public Response deleteBroker(@PathParam("broker-id") String brokerId) {
        service.deleteBroker(brokerId);
        return Response.ok(Map.of("brokerId", brokerId)).build();
    }

    @POST
    @Path("/v1/brokers/{broker-id}/reboot")
    public Response rebootBroker(@PathParam("broker-id") String brokerId) {
        service.rebootBroker(brokerId);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/v1/brokers/{broker-id}/users/{username}")
    public Response createUser(@PathParam("broker-id") String brokerId,
                               @PathParam("username") String username,
                               Map<String, Object> request) {
        MqUser user = new MqUser(
                username,
                str(request, "password"),
                bool(request, "consoleAccess"),
                strList(request.get("groups")));
        service.createUser(brokerId, user);
        return Response.ok(Map.of()).build();
    }

    @GET
    @Path("/v1/brokers/{broker-id}/users/{username}")
    public Response describeUser(@PathParam("broker-id") String brokerId,
                                 @PathParam("username") String username) {
        MqUser user = service.describeUser(brokerId, username);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brokerId", brokerId);
        body.put("username", user.getUsername());
        body.put("consoleAccess", user.isConsoleAccess());
        body.put("groups", user.getGroups());
        return Response.ok(body).build();
    }

    @GET
    @Path("/v1/brokers/{broker-id}/users")
    public Response listUsers(@PathParam("broker-id") String brokerId) {
        List<Map<String, Object>> users = new ArrayList<>();
        for (MqUser u : service.listUsers(brokerId)) {
            users.add(Map.of("username", u.getUsername()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("brokerId", brokerId);
        body.put("users", users);
        return Response.ok(body).build();
    }

    @GET
    @Path("/v1/brokers/{broker-id}/shared-resources")
    public Response describeSharedResources(@PathParam("broker-id") String brokerId) {
        // Always empty: a resource share reaches a broker only through UpdateBroker's
        // resourceShareArns, and Floci has no UpdateBroker to associate one with the broker.
        service.describeBroker(brokerId);
        return Response.ok(Map.of("sharedResources", List.of())).build();
    }

    @DELETE
    @Path("/v1/brokers/{broker-id}/users/{username}")
    public Response deleteUser(@PathParam("broker-id") String brokerId,
                               @PathParam("username") String username) {
        service.deleteUser(brokerId, username);
        return Response.ok(Map.of()).build();
    }

    private static Map<String, Object> revisionView(Revision r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("created", r.created);
        m.put("description", r.description);
        m.put("revision", r.revision);
        return m;
    }

    private static Map<String, Object> configurationView(MqConfiguration c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("arn", c.arn);
        putIfPresent(m, "authenticationStrategy", c.authenticationStrategy);
        m.put("created", c.created);
        m.put("description", c.latest().description);
        m.put("engineType", c.engineType);
        m.put("engineVersion", c.engineVersion);
        m.put("id", c.id);
        m.put("latestRevision", revisionView(c.latest()));
        m.put("name", c.name);
        m.put("tags", new LinkedHashMap<>(c.tags));
        return m;
    }

    // --- request parsing helpers ---

    private static String str(Map<String, Object> request, String key) {
        Object value = request.get(key);
        return value == null ? null : value.toString();
    }

    private static boolean bool(Map<String, Object> request, String key) {
        return Boolean.TRUE.equals(request.get(key));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                result.add(String.valueOf(o));
            }
            return result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> tags(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, String> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
            return result;
        }
        return null;
    }

    private static Map<String, Object> map(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            m.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<MqUser> parseUsers(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<MqUser> users = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> u = (Map<String, Object>) map;
                users.add(new MqUser(
                        str(u, "username"),
                        str(u, "password"),
                        bool(u, "consoleAccess"),
                        strList(u.get("groups"))));
            }
        }
        return users;
    }
}
