package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edges of the Fargate rules: the boundary values of the documented ranges, the parameters
 * that are only invalid in combination, and the limits AWS puts on a single call.
 */
@QuarkusTest
class EcsFargateEdgeCaseIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";
    private static final String CLUSTER = "fargate-edge-cluster";
    private static final String NETWORK =
            "\"networkConfiguration\":{\"awsvpcConfiguration\":{\"subnets\":"
                    + "[\"subnet-default-us-east-1-a\"]}}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body, int expectedStatus) {
        return given().contentType(CT).header("X-Amz-Target", TARGET + action)
                .body(body)
                .when().post("/")
                .then().statusCode(expectedStatus)
                .extract().response();
    }

    private static void registerSize(String family, String cpu, String memory, int expectedStatus) {
        call("RegisterTaskDefinition", "{\"family\":\"" + family + "\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"" + cpu + "\",\"memory\":\"" + memory + "\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}",
                expectedStatus);
    }

    private static String seed(String family) {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        registerSize(family, "256", "512", 200);
        return family;
    }

    // ── Task size ─────────────────────────────────────────────────────────────

    @Test
    void theEdgesOfEveryFargateSizeRangeAreAccepted() {
        registerSize("edge-256-low", "256", "512", 200);
        registerSize("edge-256-high", "256", "2048", 200);
        registerSize("edge-512-low", "512", "1024", 200);
        registerSize("edge-512-high", "512", "4096", 200);
        registerSize("edge-2048-low", "2048", "4096", 200);
        registerSize("edge-2048-high", "2048", "16384", 200);
        registerSize("edge-4096-high", "4096", "30720", 200);
        registerSize("edge-8192-low", "8192", "16384", 200);
        registerSize("edge-16384-high", "16384", "122880", 200);
    }

    @Test
    void justOutsideEveryFargateSizeRangeIsRejected() {
        registerSize("edge-256-below", "256", "256", 400);
        registerSize("edge-256-above", "256", "3072", 400);
        registerSize("edge-512-below", "512", "512", 400);
        registerSize("edge-512-above", "512", "5120", 400);
        registerSize("edge-4096-above", "4096", "31744", 400);
        registerSize("edge-16384-above", "16384", "131072", 400);
        registerSize("edge-unknown-cpu", "384", "1024", 400);
    }

    @Test
    void theThirtyTwoVcpuSizesAreTheThreeFargateOffers() {
        registerSize("edge-32vcpu-60", "32768", "61440", 200);
        registerSize("edge-32vcpu-120", "32768", "122880", 200);
        registerSize("edge-32vcpu-244", "32768", "249856", 200);
        // 32 vCPU is not a range: anything between the three offers is not a configuration.
        registerSize("edge-32vcpu-between", "32768", "65536", 400);
    }

    @Test
    void theStringFormsOfCpuAndMemoryAreAccepted() {
        registerSize("edge-vcpu-string", "0.5 vCPU", "1 GB", 200);
        registerSize("edge-vcpu-lowercase", "1 vcpu", "2GB", 200);
    }

    // ── Ephemeral storage ─────────────────────────────────────────────────────

    @Test
    void ephemeralStorageIsAcceptedAtBothEndsOfItsRange() {
        String body = "{\"family\":\"edge-storage-%s\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"256\",\"memory\":\"512\","
                + "\"ephemeralStorage\":{\"sizeInGiB\":%d},"
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}";

        call("RegisterTaskDefinition", body.formatted("min", 21), 200);
        call("RegisterTaskDefinition", body.formatted("max", 200), 200);
        call("RegisterTaskDefinition", body.formatted("below", 20), 400);
        call("RegisterTaskDefinition", body.formatted("above", 201), 400);
    }

    // ── Parameters Fargate does not support ───────────────────────────────────

    @Test
    void everyFargateUnsupportedParameterIsRejected() {
        String container = "{\"family\":\"edge-unsupported-%s\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\",\"containerDefinitions\":"
                + "[{\"name\":\"app\",\"image\":\"nginx:latest\",%s}]}";

        call("RegisterTaskDefinition",
                container.formatted("dso", "\"dockerSecurityOptions\":[\"label:user:jdoe\"]"), 400)
                .then().body("message", containsString("dockerSecurityOptions"));
        call("RegisterTaskDefinition",
                container.formatted("hosts", "\"extraHosts\":[{\"hostname\":\"db\",\"ipAddress\":\"10.0.0.9\"}]"), 400)
                .then().body("message", containsString("extraHosts"));
        call("RegisterTaskDefinition",
                container.formatted("gpu", "\"resourceRequirements\":[{\"type\":\"GPU\",\"value\":\"1\"}]"), 400)
                .then().body("message", containsString("gpu"));
        call("RegisterTaskDefinition",
                container.formatted("swap", "\"linuxParameters\":{\"maxSwap\":128}"), 400)
                .then().body("message", containsString("maxSwap"));
        call("RegisterTaskDefinition",
                container.formatted("swappiness", "\"linuxParameters\":{\"swappiness\":10}"), 400)
                .then().body("message", containsString("swappiness"));

        call("RegisterTaskDefinition", "{\"family\":\"edge-unsupported-placement\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\","
                + "\"placementConstraints\":[{\"type\":\"memberOf\",\"expression\":\"attribute:ecs.os-type==linux\"}],"
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 400)
                .then().body("message", containsString("placementConstraints"));
    }

    @Test
    void pidModeTaskIsTheOnlyOneFargateAccepts() {
        String body = "{\"family\":\"edge-pidmode-%s\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"256\",\"memory\":\"512\",\"pidMode\":\"%s\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}";

        call("RegisterTaskDefinition", body.formatted("task", "task"), 200);
        call("RegisterTaskDefinition", body.formatted("host", "host"), 400)
                .then().body("message", containsString("pidMode"));
    }

    @Test
    void aDependencyCycleIsRejectedAtRegistration() {
        call("RegisterTaskDefinition", "{\"family\":\"edge-cycle\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\",\"containerDefinitions\":["
                + "{\"name\":\"a\",\"image\":\"busybox\","
                + "\"dependsOn\":[{\"containerName\":\"b\",\"condition\":\"START\"}]},"
                + "{\"name\":\"b\",\"image\":\"busybox\","
                + "\"dependsOn\":[{\"containerName\":\"a\",\"condition\":\"START\"}]}]}", 400)
                .then().body("message", containsString("cycle"));
    }

    // ── RunTask limits ────────────────────────────────────────────────────────

    @Test
    void runTaskPlacesAtMostTenTasksPerCall() {
        String family = seed("edge-count");

        Response ten = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"count\":10,\"launchType\":\"FARGATE\"," + NETWORK + "}", 200);
        ten.then().body("tasks", hasSize(10));

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"count\":11,\"launchType\":\"FARGATE\"," + NETWORK + "}", 400)
                .then().body("message", containsString("between 1 and 10"));
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"count\":0,\"launchType\":\"FARGATE\"," + NETWORK + "}", 400);

        ten.jsonPath().getList("tasks.taskArn", String.class).forEach(taskArn ->
                call("StopTask", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\"" + taskArn + "\"}", 200));
    }

    @Test
    void propagatingServiceTagsIsRefusedForAStandaloneTask() {
        String family = seed("edge-propagate");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\"," + NETWORK + ",\"propagateTags\":\"SERVICE\"}", 400)
                .then().body("message", containsString("SERVICE"));
    }

    @Test
    void propagatingTaskDefinitionTagsCopiesThemOntoTheTask() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        call("RegisterTaskDefinition", "{\"family\":\"edge-td-tags\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\",\"tags\":[{\"key\":\"owner\",\"value\":\"platform\"}],"
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 200);

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"edge-td-tags\","
                + "\"launchType\":\"FARGATE\"," + NETWORK + ",\"propagateTags\":\"TASK_DEFINITION\"}", 200)
                .then()
                .body("tasks[0].tags[0].key", equalTo("owner"))
                .body("tasks[0].tags[0].value", equalTo("platform"));
    }

    /**
     * The ENI allocation reaches into EC2, whose not-found codes RunTask does not declare. An SDK
     * given {@code InvalidSubnetID.NotFound} sees an unmodelled failure instead of the
     * InvalidParameterException AWS returns for a subnet that is not there.
     */
    @Test
    void runTaskRejectsAnUnknownSubnetAsAnInvalidParameter() {
        String family = seed("edge-unknown-subnet");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\",\"networkConfiguration\":{\"awsvpcConfiguration\":"
                + "{\"subnets\":[\"subnet-does-not-exist\"]}}}", 400)
                .then()
                .body("__type", containsString("InvalidParameterException"))
                .body("message", containsString("subnet-does-not-exist"));
    }

    @Test
    void runTaskRejectsAnUnknownSecurityGroupAsAnInvalidParameter() {
        String family = seed("edge-unknown-sg");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\",\"networkConfiguration\":{\"awsvpcConfiguration\":"
                + "{\"subnets\":[\"subnet-default-us-east-1-a\"],\"securityGroups\":[\"sg-nope\"]}}}", 400)
                .then()
                .body("__type", containsString("InvalidParameterException"))
                .body("message", containsString("sg-nope"));
    }

    /**
     * MANAGED_INSTANCES joined LaunchType and Compatibility in the ECS model. Rejecting it would
     * 400 a request the SDK considers valid.
     */
    @Test
    void managedInstancesIsAcceptedAsACompatibilityAndALaunchType() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        call("RegisterTaskDefinition", "{\"family\":\"edge-managed-instances\","
                + "\"requiresCompatibilities\":[\"EC2\",\"MANAGED_INSTANCES\"],"
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\","
                + "\"memory\":512}]}", 200)
                .then().body("taskDefinition.requiresCompatibilities", hasItems("MANAGED_INSTANCES"));

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"edge-managed-instances\","
                + "\"launchType\":\"MANAGED_INSTANCES\"}", 200)
                .then().body("tasks[0].launchType", equalTo("MANAGED_INSTANCES"));
    }

    // ── Capacity provider strategy ────────────────────────────────────────────

    @Test
    void aStrategyWithTwoBasesIsRejected() {
        String family = seed("edge-two-bases");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + NETWORK + ",\"capacityProviderStrategy\":["
                + "{\"capacityProvider\":\"FARGATE\",\"base\":1,\"weight\":1},"
                + "{\"capacityProvider\":\"FARGATE_SPOT\",\"base\":1,\"weight\":1}]}", 400)
                .then().body("message", containsString("base"));
    }

    @Test
    void aLoneProviderOfWeightZeroStillPlacesEveryTask() {
        String family = seed("edge-zero-weight");

        Response response = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"count\":3," + NETWORK + ",\"capacityProviderStrategy\":"
                + "[{\"capacityProvider\":\"FARGATE_SPOT\",\"weight\":0}]}", 200);

        List<String> providers = response.jsonPath().getList("tasks.capacityProviderName");
        assertEquals(3, providers.size(), "a weightless strategy must still place every task");
        assertTrue(providers.stream().allMatch("FARGATE_SPOT"::equals), providers.toString());
    }

    @Test
    void aStrategyWhoseProvidersAllWeighZeroIsRejected() {
        String family = seed("edge-all-zero-weights");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + NETWORK + ",\"capacityProviderStrategy\":["
                + "{\"capacityProvider\":\"FARGATE\",\"weight\":0},"
                + "{\"capacityProvider\":\"FARGATE_SPOT\",\"weight\":0}]}", 400)
                .then().body("message", containsString("weight greater than zero"));
    }

    @Test
    void aStrategyEntryOutsideTheWeightAndBaseRangesIsRejected() {
        String family = seed("edge-strategy-ranges");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + NETWORK + ",\"capacityProviderStrategy\":"
                + "[{\"capacityProvider\":\"FARGATE\",\"weight\":1001}]}", 400)
                .then().body("message", containsString("between 0 and 1000"));
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + NETWORK + ",\"capacityProviderStrategy\":"
                + "[{\"capacityProvider\":\"FARGATE\",\"weight\":1,\"base\":100001}]}", 400)
                .then().body("message", containsString("between 0 and 100000"));

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + NETWORK + ",\"capacityProviderStrategy\":"
                + "[{\"capacityProvider\":\"FARGATE\",\"weight\":1000,\"base\":100000}]}", 200)
                .then().body("tasks[0].capacityProviderName", equalTo("FARGATE"));
    }

    @Test
    void aStrategyOfMoreThanTwentyProvidersIsRejected() {
        String family = seed("edge-strategy-size");
        StringBuilder strategy = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            strategy.append(i == 0 ? "" : ",")
                    .append("{\"capacityProvider\":\"FARGATE\",\"weight\":1}");
        }

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + NETWORK + ",\"capacityProviderStrategy\":[" + strategy + "]}", 400)
                .then().body("message", containsString("maximum of 20 capacity providers"));
    }

    @Test
    void anEmptyStrategyFallsBackToTheLaunchType() {
        String family = seed("edge-empty-strategy");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\"," + NETWORK + ",\"capacityProviderStrategy\":[]}", 200)
                .then().body("tasks[0].launchType", equalTo("FARGATE"));
    }

    // ── Task shape ────────────────────────────────────────────────────────────

    @Test
    void aStoppedTaskReportsTheTimestampsAndCodesAwsReports() {
        String family = seed("edge-stopped-shape");
        String taskArn = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"launchType\":\"FARGATE\"," + NETWORK + "}", 200)
                .jsonPath().getString("tasks[0].taskArn");

        Response stopped = call("StopTask", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\""
                + taskArn + "\",\"reason\":\"edge\"}", 200);

        stopped.then()
                .body("task.stopCode", equalTo("UserInitiated"))
                .body("task.lastStatus", equalTo("STOPPED"));
        assertTrue(stopped.jsonPath().getDouble("task.stoppingAt") > 0,
                "stoppingAt must be reported for a task that went through STOPPING");
        assertTrue(stopped.jsonPath().getDouble("task.executionStoppedAt") > 0,
                "executionStoppedAt must be reported");
    }

    @Test
    void aFargateTaskReportsBothEphemeralStorageMembersAndZeroCpuContainers() {
        String family = seed("edge-storage-members");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\"," + NETWORK + "}", 200)
                .then()
                .body("tasks[0].ephemeralStorage.sizeInGiB", equalTo(20))
                .body("tasks[0].fargateEphemeralStorage.sizeInGiB", equalTo(20))
                // A container definition without cpu units reports zero, not nothing.
                .body("tasks[0].containers[0].cpu", equalTo("0"));
    }

    @Test
    void describeTasksReportsTagsOnlyWhenTheyAreAskedFor() {
        String family = seed("edge-describe-tags");
        String taskArn = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"launchType\":\"FARGATE\"," + NETWORK
                + ",\"tags\":[{\"key\":\"owner\",\"value\":\"platform\"}]}", 200)
                .jsonPath().getString("tasks[0].taskArn");

        call("DescribeTasks", "{\"cluster\":\"" + CLUSTER + "\",\"tasks\":[\"" + taskArn + "\"]}", 200)
                .then().body("tasks[0].tags", nullValue());

        call("DescribeTasks", "{\"cluster\":\"" + CLUSTER + "\",\"tasks\":[\"" + taskArn
                + "\"],\"include\":[\"TAGS\"]}", 200)
                .then().body("tasks[0].tags[0].key", equalTo("owner"));
    }

    @Test
    void aTaskAlwaysReportsAnOverrideEntryPerContainer() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        call("RegisterTaskDefinition", "{\"family\":\"edge-overrides\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\",\"containerDefinitions\":["
                + "{\"name\":\"app\",\"image\":\"nginx:latest\"},"
                + "{\"name\":\"sidecar\",\"image\":\"busybox\",\"essential\":false}]}", 200);

        // Nothing was overridden, and AWS still lists every container under overrides.
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"edge-overrides\","
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}", 200)
                .then()
                .body("tasks[0].overrides.containerOverrides", hasSize(2))
                .body("tasks[0].overrides.containerOverrides[0].name", equalTo("app"))
                .body("tasks[0].overrides.containerOverrides[1].name", equalTo("sidecar"));
    }

    @Test
    void anAwsvpcTaskReportsTheAttachmentDetailsAwsReports() {
        String family = seed("edge-attachment-details");
        Response response = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"launchType\":\"FARGATE\"," + NETWORK + "}", 200);

        List<String> names = response.jsonPath().getList("tasks[0].attachments[0].details.name");
        assertTrue(names.containsAll(List.of("subnetId", "networkInterfaceId", "macAddress",
                        "privateDnsName", "privateIPv4Address")),
                "the attachment must carry the details AWS reports, got: " + names);
    }

    // ── Service updates ──────────────────────────────────────────────────────

    private static String deploymentId(String serviceName) {
        return call("DescribeServices", "{\"cluster\":\"" + CLUSTER + "\",\"services\":[\""
                + serviceName + "\"]}", 200)
                .jsonPath().getString("services[0].deployments[0].id");
    }

    // ── Task definition lifecycle ─────────────────────────────────────────────

    @Test
    void aDeletedRevisionStaysDescribableAsDeleteInProgress() {
        registerSize("edge-delete-lifecycle", "256", "512", 200);

        // Deleting before deregistering is refused, and a family without a revision is not a
        // reference this API takes.
        call("DeleteTaskDefinitions", "{\"taskDefinitions\":[\"edge-delete-lifecycle:1\"]}", 400)
                .then().body("message", containsString("INACTIVE"));
        call("DeregisterTaskDefinition", "{\"taskDefinition\":\"edge-delete-lifecycle:1\"}", 200)
                .then().body("taskDefinition.status", equalTo("INACTIVE"));
        call("DeleteTaskDefinitions", "{\"taskDefinitions\":[\"edge-delete-lifecycle\"]}", 400)
                .then().body("message", containsString("revision"));

        call("DeleteTaskDefinitions", "{\"taskDefinitions\":[\"edge-delete-lifecycle:1\"]}", 200)
                .then().body("taskDefinitions[0].status", equalTo("DELETE_IN_PROGRESS"));

        // The revision is still describable, and carries the lifecycle timestamps.
        Response described = call("DescribeTaskDefinition",
                "{\"taskDefinition\":\"edge-delete-lifecycle:1\"}", 200);
        described.then().body("taskDefinition.status", equalTo("DELETE_IN_PROGRESS"));
        assertTrue(described.jsonPath().getDouble("taskDefinition.deregisteredAt") > 0,
                "a deregistered revision reports deregisteredAt");
        assertTrue(described.jsonPath().getDouble("taskDefinition.deleteRequestedAt") > 0,
                "a revision being deleted reports deleteRequestedAt");

        // And it can no longer start anything new.
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"edge-delete-lifecycle:1\","
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}", 400)
                .then().body("message", containsString("being deleted"));
    }

    @Test
    void describeTasksReportsAnUnknownTaskAsMissingRatherThanDroppingIt() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);

        call("DescribeTasks", "{\"cluster\":\"" + CLUSTER + "\",\"tasks\":[\"00000000000000000000000000000000\"]}", 200)
                .then()
                .body("tasks", hasSize(0))
                .body("failures", hasSize(1))
                .body("failures[0].reason", equalTo("MISSING"));
    }

    // ── Derived compatibilities and requiresAttributes ────────────────────────

    @Test
    void compatibilitiesAreDerivedFromTheDefinitionNotJustEchoed() {
        // Fargate-valid but never asked for FARGATE: ECS still reports the launch types it works on.
        call("RegisterTaskDefinition", "{\"family\":\"edge-compat-implicit\",\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\",\"containerDefinitions\":"
                + "[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 200)
                .then().body("taskDefinition.compatibilities", equalTo(List.of("EC2", "FARGATE")));

        // awsvpc rules out EXTERNAL; bridge rules out FARGATE and allows EXTERNAL.
        call("RegisterTaskDefinition", "{\"family\":\"edge-compat-bridge\",\"networkMode\":\"bridge\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\",\"memory\":128}]}", 200)
                .then().body("taskDefinition.compatibilities", equalTo(List.of("EC2", "EXTERNAL")));

        // awsvpc without a Fargate size pair is EC2 only.
        call("RegisterTaskDefinition", "{\"family\":\"edge-compat-nosize\",\"networkMode\":\"awsvpc\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\",\"memory\":128}]}", 200)
                .then().body("taskDefinition.compatibilities", equalTo(List.of("EC2")));
    }

    @Test
    void requiresAttributesNamesTheCapabilitiesTheDefinitionUses() {
        call("RegisterTaskDefinition", "{\"family\":\"edge-attrs\",\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\","
                + "\"taskRoleArn\":\"arn:aws:iam::000000000000:role/task\","
                + "\"containerDefinitions\":[{\"name\":\"app\","
                + "\"image\":\"000000000000.dkr.ecr.us-east-1.amazonaws.com/app:1\","
                + "\"logConfiguration\":{\"logDriver\":\"awslogs\",\"options\":{}}}]}", 200)
                .then()
                .body("taskDefinition.requiresAttributes.name", hasItems(
                        "com.amazonaws.ecs.capability.docker-remote-api.1.18",
                        "ecs.capability.task-eni",
                        "com.amazonaws.ecs.capability.task-iam-role",
                        "com.amazonaws.ecs.capability.ecr-auth",
                        "com.amazonaws.ecs.capability.logging-driver.awslogs"));

        // A task role under host networking has its own capability name.
        call("RegisterTaskDefinition", "{\"family\":\"edge-attrs-host\",\"networkMode\":\"host\","
                + "\"taskRoleArn\":\"arn:aws:iam::000000000000:role/task\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\",\"memory\":128}]}", 200)
                .then()
                .body("taskDefinition.requiresAttributes.name", hasItems(
                        "com.amazonaws.ecs.capability.task-iam-role-network-host"))
                .body("taskDefinition.requiresAttributes.name", not(hasItems("ecs.capability.task-eni")));
    }

    @Test
    void startTaskRequiresContainerInstances() {
        String family = seed("edge-start-task");

        call("StartTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\"}", 400)
                .then().body("message", containsString("containerInstances"));
    }

    // ── Capacity providers ───────────────────────────────────────────────────

    // ── Round trips the parser used to drop ──────────────────────────────────

    @Test
    void aPortMappingRoundTripsItsServiceConnectMembers() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        call("RegisterTaskDefinition", "{\"family\":\"edge-portmapping\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\","
                + "\"portMappings\":[{\"containerPort\":8080,\"protocol\":\"tcp\","
                + "\"name\":\"api\",\"appProtocol\":\"http\"}]}]}", 200);

        // name and appProtocol are what a service's serviceConnectConfiguration references, so
        // losing them on the round trip breaks Service Connect.
        call("DescribeTaskDefinition", "{\"taskDefinition\":\"edge-portmapping\"}", 200)
                .then()
                .body("taskDefinition.containerDefinitions[0].portMappings[0].name", equalTo("api"))
                .body("taskDefinition.containerDefinitions[0].portMappings[0].appProtocol",
                        equalTo("http"))
                .body("taskDefinition.containerDefinitions[0].portMappings[0].containerPort",
                        equalTo(8080));
    }

    @Test
    void aVolumeRoundTripsTheConfigurationsFlociDoesNotBack() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        call("RegisterTaskDefinition", "{\"family\":\"edge-volume-roundtrip\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}],"
                + "\"volumes\":[{\"name\":\"docker-vol\",\"configuredAtLaunch\":false,"
                + "\"dockerVolumeConfiguration\":{\"scope\":\"shared\",\"autoprovision\":true,"
                + "\"driver\":\"local\"}}]}", 200);

        call("DescribeTaskDefinition", "{\"taskDefinition\":\"edge-volume-roundtrip\"}", 200)
                .then()
                .body("taskDefinition.volumes[0].name", equalTo("docker-vol"))
                .body("taskDefinition.volumes[0].dockerVolumeConfiguration.scope", equalTo("shared"))
                .body("taskDefinition.volumes[0].dockerVolumeConfiguration.driver", equalTo("local"))
                .body("taskDefinition.volumes[0].configuredAtLaunch", equalTo(false));
    }

    // ── Request validation ───────────────────────────────────────────────────

    @Test
    void anEnumValueTheApiDoesNotHaveIsRejectedRatherThanIgnored() {
        String family = seed("edge-bad-enum");

        // Silently treating this as absent would place the task on Floci's default launch type,
        // which is not what the caller asked for.
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"EC22\"," + NETWORK + "}", 400)
                .then().body("message", containsString("launchType"));

        call("RegisterTaskDefinition", "{\"family\":\"edge-bad-network-mode\","
                + "\"networkMode\":\"AWSVPC\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 400)
                .then().body("message", containsString("networkMode"));
    }

    @Test
    void aResourceTakesAtMostFiftyTags() {
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            tags.append(i > 0 ? "," : "")
                    .append("{\"key\":\"k").append(i).append("\",\"value\":\"v\"}");
        }
        call("CreateCluster", "{\"clusterName\":\"edge-too-many-tags\",\"tags\":[" + tags + "]}", 400)
                .then().body("message", containsString("50"));

        call("CreateCluster", "{\"clusterName\":\"edge-empty-tag-key\","
                + "\"tags\":[{\"key\":\"\",\"value\":\"v\"}]}", 400)
                .then().body("message", containsString("Tag keys"));
    }
}
