package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.xml.HasXPath.hasXPath;

/**
 * MonitorInstances and UnmonitorInstances. Detailed monitoring has no emulated
 * behaviour behind it, but the calls have to be answered: one unsupported action fails
 * a whole deployment regardless of how much of the rest of it succeeded.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_MonitorInstances.html">MonitorInstances</a>
 */
@QuarkusTest
class Ec2MonitorInstancesTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void monitorInstancesReportsMonitoringEnabledForEachInstance() {
        given()
            .formParam("Action", "MonitorInstances")
            .formParam("InstanceId.1", "i-1234567890abcdef0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("MonitorInstancesResponse.instancesSet.item.instanceId",
                    equalTo("i-1234567890abcdef0"))
            .body("MonitorInstancesResponse.instancesSet.item.monitoring.state",
                    equalTo("enabled"));
    }

    @Test
    void unmonitorInstancesReportsMonitoringDisabled() {
        given()
            .formParam("Action", "UnmonitorInstances")
            .formParam("InstanceId.1", "i-1234567890abcdef0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("UnmonitorInstancesResponse.instancesSet.item.instanceId",
                    equalTo("i-1234567890abcdef0"))
            .body("UnmonitorInstancesResponse.instancesSet.item.monitoring.state",
                    equalTo("disabled"));
    }

    @Test
    void everyRequestedInstanceIsEchoedBack() {
        // InstanceId.N is a list; a caller enabling monitoring across a fleet has to see
        // every instance it named, not just the first.
        given()
            .formParam("Action", "MonitorInstances")
            .formParam("InstanceId.1", "i-1234567890abcdef0")
            .formParam("InstanceId.2", "i-0598c7d356eba48d7")
            .formParam("InstanceId.3", "i-0abcdef1234567890")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("count(//*[local-name()='instancesSet']/*[local-name()='item'])", equalTo("3")))
            .body(hasXPath("//*[local-name()='instancesSet']/*[local-name()='item'][1]/*[local-name()='instanceId']", equalTo("i-1234567890abcdef0")))
            .body(hasXPath("//*[local-name()='instancesSet']/*[local-name()='item'][2]/*[local-name()='instanceId']", equalTo("i-0598c7d356eba48d7")))
            .body(hasXPath("//*[local-name()='instancesSet']/*[local-name()='item'][3]/*[local-name()='instanceId']", equalTo("i-0abcdef1234567890")));
    }

    @Test
    void theResponseCarriesARequestId() {
        given()
            .formParam("Action", "MonitorInstances")
            .formParam("InstanceId.1", "i-1234567890abcdef0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("MonitorInstancesResponse.requestId", org.hamcrest.Matchers.notNullValue());
    }
}
