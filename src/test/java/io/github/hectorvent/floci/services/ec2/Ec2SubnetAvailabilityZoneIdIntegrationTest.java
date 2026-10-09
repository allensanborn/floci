package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CreateSubnet never read AvailabilityZoneId and hardcoded every new subnet's zone id to the
 * region's first zone, so a subnet asked for us-east-1-az2 came back in us-east-1-az1, and even
 * a subnet placed correctly by zone <em>name</em> reported a zone id that contradicted it.
 * Anything selecting subnets by zone id (Terraform's aws_subnet.availability_zone_id, and any
 * client pairing subnets with the DescribeAvailabilityZones list) therefore saw one zone.
 */
@QuarkusTest
class Ec2SubnetAvailabilityZoneIdIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private String vpcId;

    @BeforeEach
    void createVpc() {
        vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.90.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    @Test
    void theZoneIdFollowsTheZoneTheSubnetWasPlacedIn() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.1.0/24")
            .formParam("AvailabilityZone", "us-east-1b")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSubnetResponse.subnet.availabilityZone", equalTo("us-east-1b"))
            .body("CreateSubnetResponse.subnet.availabilityZoneId", equalTo("us-east-1-az2"));
    }

    @Test
    void aSubnetCanBePlacedByZoneIdAlone() {
        String subnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.2.0/24")
            .formParam("AvailabilityZoneId", "us-east-1-az3")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSubnetResponse.subnet.availabilityZoneId", equalTo("us-east-1-az3"))
            .body("CreateSubnetResponse.subnet.availabilityZone", equalTo("us-east-1c"))
            .extract().path("CreateSubnetResponse.subnet.subnetId");

        // ... and it reads back the same way, not just in the create response.
        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("SubnetId.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSubnetsResponse.subnetSet.item.availabilityZoneId", equalTo("us-east-1-az3"));
    }

    /**
     * The zone id a subnet reports has to be one DescribeAvailabilityZones actually publishes,
     * or a client that pairs the two lists finds no subnet in the zone it just looked up.
     */
    @Test
    void theZoneIdMatchesTheZoneListTheSameClientCanRead() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.3.0/24")
            .formParam("AvailabilityZone", "us-east-1c")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSubnetResponse.subnet.availabilityZoneId", equalTo("us-east-1-az3"));

        given()
            .formParam("Action", "DescribeAvailabilityZones")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeAvailabilityZonesResponse.availabilityZoneInfo.item.zoneId",
                    hasItem("us-east-1-az3"));
    }

    @Test
    void aZoneAndZoneIdThatDisagreeAreRefusedRatherThanSilentlyResolved() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.4.0/24")
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("AvailabilityZoneId", "us-east-1-az2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
    }

    @Test
    void aZoneAndZoneIdNamingTheSameZoneAreAccepted() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.5.0/24")
            .formParam("AvailabilityZone", "us-east-1b")
            .formParam("AvailabilityZoneId", "us-east-1-az2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSubnetResponse.subnet.availabilityZone", equalTo("us-east-1b"));
    }

    /**
     * A well-formed id past the zones this region publishes is refused too. Resolving it would put
     * the subnet in a zone DescribeAvailabilityZones does not list, leaving a client unable to
     * pair the two: the same inconsistency the hardcoded az1 produced, only quieter.
     */
    @Test
    void aZoneIdBeyondThePublishedZonesIsRefused() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.7.0/24")
            .formParam("AvailabilityZoneId", "us-east-1-az4")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
            .body("Response.Errors.Error.Message", containsString("us-east-1-az1 to us-east-1-az3"));
    }

    @Test
    void aMalformedZoneIdIsRefused() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.6.0/24")
            .formParam("AvailabilityZoneId", "not-a-zone-id")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    /**
     * A zone name outside the zones this region publishes is refused, as an unpublished zone id
     * is. Accepted, us-east-1f was given the derived id us-east-1-az6 and zzz fell back to
     * us-east-1-az1, neither of which pairs with the zone list DescribeAvailabilityZones returns.
     */
    @ParameterizedTest
    @ValueSource(strings = {"us-east-1f", "zzz", "us-west-2a"})
    void aZoneNameOutsideThePublishedZonesIsRefusedAndNothingIsStored(String zone) {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.8.0/24")
            .formParam("AvailabilityZone", zone)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
            .body("Response.Errors.Error.Message", containsString("us-east-1a, us-east-1b, us-east-1c"));

        assertEquals(List.of(), subnetIdsInVpc());
    }

    /** An unpublished name is the fault even when the id beside it is valid, not a mismatch. */
    @Test
    void anUnpublishedZoneNameWithAValidZoneIdIsRefusedAsInvalid() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.9.0/24")
            .formParam("AvailabilityZone", "us-east-1f")
            .formParam("AvailabilityZoneId", "us-east-1-az1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    /**
     * Every zone a subnet can be placed in by name reads back, through DescribeSubnets, with the
     * zone id DescribeAvailabilityZones pairs with that name.
     */
    @ParameterizedTest
    @ValueSource(strings = {"us-east-1a", "us-east-1b", "us-east-1c"})
    void eachAcceptedZoneNameReadsBackWithThePublishedZoneId(String zone) {
        String subnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.10.0/24")
            .formParam("AvailabilityZone", zone)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");

        String published = given()
            .formParam("Action", "DescribeAvailabilityZones")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("DescribeAvailabilityZonesResponse.availabilityZoneInfo.item.find { it.zoneName == '"
                    + zone + "' }.zoneId");

        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("SubnetId.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSubnetsResponse.subnetSet.item.availabilityZone", equalTo(zone))
            .body("DescribeSubnetsResponse.subnetSet.item.availabilityZoneId", equalTo(published));
    }

    private List<String> subnetIdsInVpc() {
        return given()
            .formParam("Action", "DescribeSubnets")
            .formParam("Filter.1.Name", "vpc-id")
            .formParam("Filter.1.Value.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getList("DescribeSubnetsResponse.subnetSet.item.subnetId");
    }
}
