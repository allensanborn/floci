package io.floci.aws.services.ecs;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Pins the exact error code, message and status ECS answers for the not-found and refused-delete
 * cases, each worded as AWS words it for that operation. ECS is awsJson1_1, so the message rides
 * in {@code message} and the code in {@code __type}.
 */
@QuarkusTest
class EcsErrorMessagesIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";
    private static final String CONTAINERS =
            "[{\"name\":\"web\",\"image\":\"nginx\",\"memory\":128}]";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", TARGET + action).body(body)
                .when().post("/").then().extract().response();
    }

    private static void ok(String action, String body) {
        call(action, body).then().statusCode(200);
    }

    private static void expectError(String action, String body, int status, String code, String message) {
        call(action, body).then()
                .statusCode(status)
                .body("__type", equalTo(code))
                .body("message", equalTo(message));
    }

    private static String registerTaskDef(String family) {
        return call("RegisterTaskDefinition",
                "{\"family\":\"" + family + "\",\"containerDefinitions\":" + CONTAINERS + "}")
                .jsonPath().getString("taskDefinition.taskDefinitionArn");
    }

    // ── ClusterNotFoundException names no cluster ─────────────────────────────

    @Test
    void missingClusterIsReportedWithoutItsName() {
        String td = registerTaskDef("errmsg-cluster-td");
        String missing = "errmsg-no-such-cluster";
        String notFound = "Cluster not found.";
        expectError("ListServices", "{\"cluster\":\"" + missing + "\"}", 400, "ClusterNotFoundException", notFound);
        expectError("DescribeServices", "{\"cluster\":\"" + missing + "\",\"services\":[\"s\"]}",
                400, "ClusterNotFoundException", notFound);
        expectError("DescribeTasks", "{\"cluster\":\"" + missing + "\",\"tasks\":[\"t\"]}",
                400, "ClusterNotFoundException", notFound);
        expectError("RunTask", "{\"cluster\":\"" + missing + "\",\"taskDefinition\":\"" + td + "\"}",
                400, "ClusterNotFoundException", notFound);
        expectError("CreateService", "{\"cluster\":\"" + missing + "\",\"serviceName\":\"s\","
                + "\"taskDefinition\":\"" + td + "\",\"desiredCount\":0}",
                400, "ClusterNotFoundException", notFound);
        expectError("PutClusterCapacityProviders", "{\"cluster\":\"" + missing + "\","
                + "\"capacityProviders\":[\"FARGATE\"],\"defaultCapacityProviderStrategy\":[]}",
                400, "ClusterNotFoundException", notFound);
    }

    // ── ServiceNotFoundException on a real cluster ────────────────────────────

    @Test
    void missingServiceIsWordedPerOperation() {
        ok("CreateCluster", "{\"clusterName\":\"errmsg-svc-cluster\"}");
        expectError("UpdateService", "{\"cluster\":\"errmsg-svc-cluster\",\"service\":\"nope\",\"desiredCount\":1}",
                404, "ServiceNotFoundException", "Service not found.");
        // AWS answers DeleteService on a missing service with an empty message
        expectError("DeleteService", "{\"cluster\":\"errmsg-svc-cluster\",\"service\":\"nope\"}",
                404, "ServiceNotFoundException", "");
    }

    // ── Missing task definition ───────────────────────────────────────────────

    @Test
    void missingTaskDefinitionIsWordedByWhetherItIsReadOrRun() {
        ok("CreateCluster", "{\"clusterName\":\"errmsg-td-cluster\"}");
        String describeMsg = "Unable to describe task definition.";
        expectError("DescribeTaskDefinition", "{\"taskDefinition\":\"errmsg-no-family:1\"}",
                400, "ClientException", describeMsg);
        expectError("DeregisterTaskDefinition", "{\"taskDefinition\":\"errmsg-no-family:1\"}",
                400, "ClientException", describeMsg);
        // RunTask and CreateService put the revision to work: AWS words the miss differently
        expectError("RunTask", "{\"cluster\":\"errmsg-td-cluster\",\"taskDefinition\":\"errmsg-no-family:1\"}",
                400, "ClientException", "TaskDefinition not found.");
        expectError("CreateService", "{\"cluster\":\"errmsg-td-cluster\",\"serviceName\":\"s\","
                + "\"taskDefinition\":\"errmsg-no-family:1\",\"desiredCount\":0}",
                400, "ClientException", "TaskDefinition not found.");
    }

    // ── DeleteService without force on a scaled service ───────────────────────

    @Test
    void deleteScaledServiceWithoutForceIsRefused() {
        ok("CreateCluster", "{\"clusterName\":\"errmsg-del-svc-cluster\"}");
        String td = registerTaskDef("errmsg-del-svc-td");
        ok("CreateService", "{\"cluster\":\"errmsg-del-svc-cluster\",\"serviceName\":\"scaled\","
                + "\"taskDefinition\":\"" + td + "\",\"desiredCount\":1}");
        expectError("DeleteService", "{\"cluster\":\"errmsg-del-svc-cluster\",\"service\":\"scaled\"}",
                400, "InvalidParameterException", "The service cannot be stopped while it is scaled above 0.");
        // the refused delete left the service alone
        call("DescribeServices", "{\"cluster\":\"errmsg-del-svc-cluster\",\"services\":[\"scaled\"]}")
                .then().body("services[0].status", equalTo("ACTIVE"));
    }

    // ── DeleteCluster with an active service ──────────────────────────────────

    @Test
    void deleteClusterWithActiveServiceIsRefused() {
        ok("CreateCluster", "{\"clusterName\":\"errmsg-del-cluster\"}");
        String td = registerTaskDef("errmsg-del-cluster-td");
        ok("CreateService", "{\"cluster\":\"errmsg-del-cluster\",\"serviceName\":\"svc\","
                + "\"taskDefinition\":\"" + td + "\",\"desiredCount\":0}");
        expectError("DeleteCluster", "{\"cluster\":\"errmsg-del-cluster\"}",
                400, "ClusterContainsServicesException",
                "The Cluster cannot be deleted while Services are active.");
        call("DescribeClusters", "{\"clusters\":[\"errmsg-del-cluster\"]}")
                .then().body("clusters[0].status", equalTo("ACTIVE"));
    }
}
