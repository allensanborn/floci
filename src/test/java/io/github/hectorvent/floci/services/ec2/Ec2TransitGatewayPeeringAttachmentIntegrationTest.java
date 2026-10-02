package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Transit gateway peering attachments over the EC2 Query protocol: the create, describe, accept,
 * tag and delete calls the Terraform provider's {@code aws_ec2_transit_gateway_peering_attachment}
 * and its {@code _accepter} make. Every state change is read back through a separate describe
 * rather than trusted from the mutating call's own response.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2TransitGatewayPeeringAttachmentIntegrationTest {

    private static final String EAST =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String WEST =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-west-2/ec2/aws4_request";
    private static final String ITEM =
            "DescribeTransitGatewayPeeringAttachmentsResponse.transitGatewayPeeringAttachments.item";

    private static String requesterTgw;
    private static String accepterTgw;
    private static String attachmentId;

    @Test
    @Order(1)
    void createTheTwoGateways() {
        requesterTgw = createTransitGateway(EAST);
        accepterTgw = createTransitGateway(EAST);
    }

    private static String createTransitGateway(String auth) {
        return given()
            .formParam("Action", "CreateTransitGateway")
            .header("Authorization", auth)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateTransitGatewayResponse.transitGateway.transitGatewayId");
    }

    @Test
    @Order(2)
    void createAwaitsAcceptance() {
        attachmentId = given()
            .formParam("Action", "CreateTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayId", requesterTgw)
            .formParam("PeerTransitGatewayId", accepterTgw)
            .formParam("PeerAccountId", "000000000000")
            .formParam("PeerRegion", "us-east-1")
            .formParam("TagSpecification.1.ResourceType", "transit-gateway-attachment")
            .formParam("TagSpecification.1.Tag.1.Key", "side")
            .formParam("TagSpecification.1.Tag.1.Value", "requester")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body("CreateTransitGatewayPeeringAttachmentResponse.transitGatewayPeeringAttachment.state",
                    equalTo("pendingAcceptance"))
            .extract().path("CreateTransitGatewayPeeringAttachmentResponse.transitGatewayPeeringAttachment"
                    + ".transitGatewayAttachmentId");

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("TransitGatewayAttachmentIds.1", attachmentId)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".state", equalTo("pendingAcceptance"))
            .body(ITEM + ".requesterTgwInfo.transitGatewayId", equalTo(requesterTgw))
            .body(ITEM + ".requesterTgwInfo.ownerId", equalTo("000000000000"))
            .body(ITEM + ".requesterTgwInfo.region", equalTo("us-east-1"))
            .body(ITEM + ".accepterTgwInfo.transitGatewayId", equalTo(accepterTgw))
            .body(ITEM + ".accepterTgwInfo.ownerId", equalTo("000000000000"))
            .body(ITEM + ".accepterTgwInfo.region", equalTo("us-east-1"))
            .body(ITEM + ".options.dynamicRouting", equalTo("disable"))
            .body(ITEM + ".tagSet.item.key", equalTo("side"))
            .body(ITEM + ".tagSet.item.value", equalTo("requester"));
    }

    /** The accepter module finds the attachment this way, by the accepter's own gateway. */
    @Test
    @Order(3)
    void theAccepterFindsItByItsGatewayAndState() {
        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("Filter.1.Name", "transit-gateway-id")
            .formParam("Filter.1.Value.1", accepterTgw)
            .formParam("Filter.2.Name", "state")
            .formParam("Filter.2.Value.1", "available")
            .formParam("Filter.2.Value.2", "pendingAcceptance")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".transitGatewayAttachmentId", equalTo(attachmentId));

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("Filter.1.Name", "state")
            .formParam("Filter.1.Value.1", "available")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".transitGatewayAttachmentId", not(hasItem(attachmentId)));
    }

    @Test
    @Order(4)
    void acceptMakesItAvailableAndTagsRoundTrip() {
        given()
            .formParam("Action", "AcceptTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", attachmentId)
            .formParam("Tag.1.Key", "side")
            .formParam("Tag.1.Value", "accepter")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("TransitGatewayAttachmentIds.1", attachmentId)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".state", equalTo("available"))
            .body(ITEM + ".tagSet.item.value", equalTo("accepter"));

        // Only a pending attachment can be accepted.
        given()
            .formParam("Action", "AcceptTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("IncorrectState"));
    }

    @Test
    @Order(5)
    void deleteRemovesIt() {
        given()
            .formParam("Action", "DeleteTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body("DeleteTransitGatewayPeeringAttachmentResponse.transitGatewayPeeringAttachment.state",
                    equalTo("deleted"));

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("TransitGatewayAttachmentIds.1", attachmentId)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidTransitGatewayAttachmentID.NotFound"));
    }

    @Test
    @Order(6)
    void anUnknownRequesterGatewayIsRefused() {
        given()
            .formParam("Action", "CreateTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayId", "tgw-0123456789abcdef0")
            .formParam("PeerTransitGatewayId", accepterTgw)
            .formParam("PeerAccountId", "000000000000")
            .formParam("PeerRegion", "us-east-1")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidTransitGatewayID.NotFound"));
    }

    /**
     * A cross-region peering is one attachment id seen from both regions, and only the accepter's
     * region can accept it.
     */
    @Test
    @Order(7)
    void crossRegionIsVisibleFromBothSidesAndAcceptedOnlyByThePeer() {
        String westTgw = createTransitGateway(WEST);
        String id = given()
            .formParam("Action", "CreateTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayId", requesterTgw)
            .formParam("PeerTransitGatewayId", westTgw)
            .formParam("PeerAccountId", "000000000000")
            .formParam("PeerRegion", "us-west-2")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateTransitGatewayPeeringAttachmentResponse.transitGatewayPeeringAttachment"
                    + ".transitGatewayAttachmentId");

        given()
            .formParam("Action", "AcceptTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(400);

        given()
            .formParam("Action", "AcceptTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", WEST)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("TransitGatewayAttachmentIds.1", id)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".state", equalTo("available"))
            .body(ITEM + ".accepterTgwInfo.region", equalTo("us-west-2"));

        given()
            .formParam("Action", "DeleteTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", WEST)
        .when().post("/")
        .then().statusCode(200);
    }
}
