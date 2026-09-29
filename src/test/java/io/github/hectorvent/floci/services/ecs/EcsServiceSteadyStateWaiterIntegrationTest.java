package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The join a steady-state wait walks, end to end (floci-rddp).
 *
 * <p>Since provider 6.x the Terraform AWS provider no longer decides {@code wait_for_steady_state}
 * from {@code DescribeServices} alone. For a service under the ECS deployment controller whose
 * PRIMARY deployment started after the operation, it:
 *
 * <ol>
 *   <li>reads {@code services[0].deployments[0].id}, which is {@code ecs-svc/<taskSetId>};</li>
 *   <li>calls {@code ListServiceDeployments} and takes the brief whose
 *       {@code targetServiceRevisionArn} <em>contains that {@code taskSetId}</em>;</li>
 *   <li>polls {@code DescribeServiceDeployments} on that ARN until its status is
 *       {@code SUCCESSFUL}.</li>
 * </ol>
 *
 * <p>Step 2 is a join AWS makes work by naming the service revision with the very id the
 * deployment reports. Floci minted the two independently, so the join matched nothing, the
 * provider never reached step 3, and every {@code wait_for_steady_state} apply sat on
 * {@code tfPENDING} for its full twenty-minute timeout against a service that was running and
 * reporting itself stable. Six of the first sixteen {@code terraform-aws-ecs} Terratest suites
 * failed that way in the 2026-09-28 sweep.
 */
@QuarkusTest
class EcsServiceSteadyStateWaiterIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";
    private static final String CLUSTER = "waiter-cluster";
    private static final String NETWORK = "\"networkConfiguration\":{\"awsvpcConfiguration\":"
            + "{\"subnets\":[\"subnet-1\"],\"securityGroups\":[\"sg-1\"]}}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", TARGET + action)
                .body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract().response();
    }

    /** Creates the cluster, a task definition and a service, and returns the service name. */
    private static String seedService(String name) {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}");
        call("RegisterTaskDefinition", "{\"family\":\"" + name + "-td\",\"networkMode\":\"awsvpc\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx\",\"memory\":128}]}");
        // desiredCount 0 is already converged, so the deployment is SUCCESSFUL without waiting on
        // the reconciler: this test is about the join, not about convergence.
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"" + name + "\","
                + "\"taskDefinition\":\"" + name + "-td\",\"desiredCount\":0,"
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}");
        return name;
    }

    /** The {@code <taskSetId>} half of the service's PRIMARY {@code ecs-svc/<taskSetId>}. */
    private static String primaryTaskSetId(String service) {
        String id = call("DescribeServices", "{\"cluster\":\"" + CLUSTER
                + "\",\"services\":[\"" + service + "\"]}")
                .jsonPath().getString("services[0].deployments[0].id");
        assertNotNull(id, "an ACTIVE service reports a PRIMARY deployment id");
        assertTrue(id.startsWith("ecs-svc/"), "deployment id is ecs-svc/<taskSetId>, was: " + id);
        return id.substring(id.indexOf('/') + 1);
    }

    @Test
    void theRevisionADeploymentTargetsIsNamedByThePrimaryDeploymentsTaskSetId() {
        String service = seedService("waiter-join-svc");
        String taskSetId = primaryTaskSetId(service);

        String targetRevision = call("ListServiceDeployments", "{\"cluster\":\"" + CLUSTER
                + "\",\"service\":\"" + service + "\"}")
                .jsonPath().getString("serviceDeployments[0].targetServiceRevisionArn");

        // Verbatim the provider's predicate: strings.Contains(targetServiceRevisionArn, taskSetID).
        assertTrue(targetRevision != null && targetRevision.contains(taskSetId),
                "targetServiceRevisionArn must carry the PRIMARY deployment's task set id "
                        + taskSetId + ", so a steady-state wait can find the deployment to poll; was: "
                        + targetRevision);
    }

    /**
     * Named for what it checks. It walks list -> join -> describe on an already-converged
     * service, so it never observes a deployment mid-flight: {@code desiredCount} is 0, which is
     * converged on arrival. Observing IN_PROGRESS over the wire would mean racing the 5-second
     * reconciler inside a {@code @QuarkusTest}, so that direction is asserted deterministically
     * in {@code EcsServiceDeploymentStatusTest} instead, and the writer echoes the field verbatim
     * ({@code EcsResponseWriter.serviceDeploymentNode}).
     */
    @Test
    void theJoinedDeploymentIsDescribable() {
        String service = seedService("waiter-walk-svc");
        String taskSetId = primaryTaskSetId(service);

        Response listed = call("ListServiceDeployments", "{\"cluster\":\"" + CLUSTER
                + "\",\"service\":\"" + service + "\"}");
        listed.then().body("serviceDeployments", hasSize(1));

        String deploymentArn = null;
        for (Object brief : listed.jsonPath().getList("serviceDeployments")) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) brief;
            Object revision = map.get("targetServiceRevisionArn");
            if (revision != null && revision.toString().contains(taskSetId)) {
                deploymentArn = String.valueOf(map.get("serviceDeploymentArn"));
            }
        }
        assertNotNull(deploymentArn, "the listing must contain the PRIMARY deployment");

        // A service at its requested task count, desiredCount 0, is a finished deployment.
        call("DescribeServiceDeployments", "{\"serviceDeploymentArns\":[\"" + deploymentArn + "\"]}")
                .then()
                .body("serviceDeployments", hasSize(1))
                .body("serviceDeployments[0].status", equalTo("SUCCESSFUL"));
    }
}
