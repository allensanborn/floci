package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * EC2 Dedicated Hosts over the Query protocol, walked through the lifecycle terraform's
 * {@code aws_ec2_host} drives: AllocateHosts, DescribeHosts until available, ModifyHosts,
 * ReleaseHosts, then DescribeHosts showing the host released. Each step is read back through
 * a separate DescribeHosts rather than trusting the mutating call's own response.
 *
 * <p>Ordered because the cases walk one host through its lifecycle.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2DedicatedHostIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String hostId;
    private static String instanceId;

    @Test
    @Order(1)
    void allocateReturnsAHostId() {
        hostId = given()
            .formParam("Action", "AllocateHosts")
            .formParam("InstanceType", "m5.large")
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("AutoPlacement", "off")
            .formParam("Quantity", "1")
            .formParam("TagSpecification.1.ResourceType", "dedicated-host")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "dh-test")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AllocateHostsResponse.hostIdSet.item", startsWith("h-"))
            .extract().path("AllocateHostsResponse.hostIdSet.item");
    }

    @Test
    @Order(2)
    void describeShowsTheHostAvailableWithItsPropertiesAndTags() {
        given()
            .formParam("Action", "DescribeHosts")
            .formParam("HostId.1", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeHostsResponse.hostSet.item.hostId", equalTo(hostId))
            .body("DescribeHostsResponse.hostSet.item.state", equalTo("available"))
            .body("DescribeHostsResponse.hostSet.item.availabilityZone", equalTo("us-east-1a"))
            .body("DescribeHostsResponse.hostSet.item.autoPlacement", equalTo("off"))
            .body("DescribeHostsResponse.hostSet.item.hostRecovery", equalTo("off"))
            .body("DescribeHostsResponse.hostSet.item.ownerId", equalTo("000000000000"))
            .body("DescribeHostsResponse.hostSet.item.hostProperties.instanceType", equalTo("m5.large"))
            // Allocated by type, so no family is echoed: terraform would read it as drift.
            .body("DescribeHostsResponse.hostSet.item.hostProperties.instanceFamily.size()", equalTo(0))
            .body("DescribeHostsResponse.hostSet.item.tagSet.item.key", equalTo("Name"))
            .body("DescribeHostsResponse.hostSet.item.tagSet.item.value", equalTo("dh-test"));
    }

    @Test
    @Order(3)
    void modifyIsReadBackThroughDescribe() {
        given()
            .formParam("Action", "ModifyHosts")
            .formParam("HostId.1", hostId)
            .formParam("AutoPlacement", "on")
            .formParam("HostRecovery", "on")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyHostsResponse.successful.item", equalTo(hostId));

        given()
            .formParam("Action", "DescribeHosts")
            .formParam("HostId.1", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeHostsResponse.hostSet.item.autoPlacement", equalTo("on"))
            .body("DescribeHostsResponse.hostSet.item.hostRecovery", equalTo("on"));
    }

    @Test
    @Order(4)
    void runInstancesOnTheHostReportsPlacementAndOccupiesIt() {
        instanceId = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-0abcdef1234567891")
            .formParam("InstanceType", "m5.large")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .formParam("Placement.HostId", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.placement.hostId",
                    equalTo(hostId))
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.placement.tenancy",
                    equalTo("host"))
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.placement.availabilityZone",
                    equalTo("us-east-1a"));

        given()
            .formParam("Action", "DescribeHosts")
            .formParam("HostId.1", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeHostsResponse.hostSet.item.instances.item.instanceId", equalTo(instanceId));
    }

    @Test
    @Order(5)
    void releaseOfAnOccupiedHostIsUnsuccessful() {
        given()
            .formParam("Action", "ReleaseHosts")
            .formParam("HostId.1", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ReleaseHostsResponse.unsuccessful.item.resourceId", equalTo(hostId))
            .body("ReleaseHostsResponse.unsuccessful.item.error.code", equalTo("Client.InvalidHost.Occupied"));

        given()
            .formParam("Action", "TerminateInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void releaseLeavesTheHostDescribableAsReleased() {
        given()
            .formParam("Action", "ReleaseHosts")
            .formParam("HostId.1", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ReleaseHostsResponse.successful.item", equalTo(hostId));

        given()
            .formParam("Action", "DescribeHosts")
            .formParam("HostId.1", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeHostsResponse.hostSet.item.state", equalTo("released"));
    }

    @Test
    @Order(7)
    void describeOfAnUnknownHostIsNotFound() {
        given()
            .formParam("Action", "DescribeHosts")
            .formParam("HostId.1", "h-00000000000000000")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidHostID.NotFound"))
            .body("Response.Errors.Error.Message", containsString("h-00000000000000000"));
    }

    @Test
    @Order(8)
    void releaseOfAnUnknownOrAlreadyReleasedHostIsUnsuccessfulNotAnError() {
        given()
            .formParam("Action", "ReleaseHosts")
            .formParam("HostId.1", "h-00000000000000000")
            .formParam("HostId.2", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ReleaseHostsResponse.unsuccessful.item.size()", equalTo(2))
            .body("ReleaseHostsResponse.unsuccessful.item[0].error.code", equalTo("Client.InvalidHostID.NotFound"))
            .body("ReleaseHostsResponse.unsuccessful.item[1].resourceId", equalTo(hostId));
    }
}
