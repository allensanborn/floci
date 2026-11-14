package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * What the transit gateway calls refuse, and with which code and message. Each refusal is followed
 * by a separate read showing nothing moved.
 */
@QuarkusTest
class Ec2TransitGatewayValidationIntegrationTest {

    private static final String EAST =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String ERROR = "Response.Errors.Error.";
    private static final String BOGUS_ROUTE_TABLE = "tgw-rtb-00000000000000000";
    private static final String BOGUS_ATTACHMENT = "tgw-attach-00000000000000000";
    private static final String BOGUS_GATEWAY = "tgw-00000000000000000";

    private static ValidatableResponse call(String action, String... params) {
        RequestSpecification request =
                given().formParam("Action", action).header("Authorization", EAST);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then();
    }

    private static void refused(ValidatableResponse response, String code, String message) {
        response.statusCode(400)
                .body(ERROR + "Code", equalTo(code))
                .body(ERROR + "Message", equalTo(message));
    }

    private static void noItems(ValidatableResponse response, String path) {
        Assertions.assertTrue(response.extract().xmlPath().getList(path).isEmpty(), path);
    }

    private static String createGateway() {
        return call("CreateTransitGateway").statusCode(200)
                .extract().path("CreateTransitGatewayResponse.transitGateway.transitGatewayId");
    }

    @Test
    void maxResultsOutsideFiveToThousandIsRefusedByEveryDescribe() {
        String[] actions = {"DescribeTransitGateways", "DescribeTransitGatewayAttachments",
                "DescribeTransitGatewayVpcAttachments", "DescribeTransitGatewayConnects",
                "DescribeTransitGatewayPeeringAttachments", "DescribeTransitGatewayRouteTables"};
        for (String action : actions) {
            call(action, "MaxResults", "5").statusCode(200);
            call(action, "MaxResults", "1000").statusCode(200);
            refused(call(action, "MaxResults", "100000"), "InvalidParameterValue",
                    "Value (100000) for parameter MaxResults is invalid. ");
            refused(call(action, "MaxResults", "4"), "InvalidParameterValue",
                    "Value (4) for parameter MaxResults is invalid. ");
        }
    }

    @Test
    void unknownFilterNameIsRefusedOnAttachmentsAndConnects() {
        refused(call("DescribeTransitGatewayAttachments",
                "Filter.1.Name", "not-a-real-filter", "Filter.1.Value.1", "x"),
                "InvalidParameterValue", "Value (not-a-real-filter) for parameter Filter is invalid. ");
        refused(call("DescribeTransitGatewayConnects",
                "Filter.1.Name", "not-a-real-filter", "Filter.1.Value.1", "x"),
                "InvalidParameterValue", "Value (not-a-real-filter) for parameter Filter is invalid. ");
        noItems(call("DescribeTransitGatewayConnects",
                "Filter.1.Name", "transit-gateway-attachment-id", "Filter.1.Value.1", BOGUS_ATTACHMENT)
                .statusCode(200), "DescribeTransitGatewayConnectsResponse.transitGatewayConnectSet.item");
    }

    @Test
    void routeTableReadsOfAnUnknownTableAreRefused() {
        String message = "Transit Gateway Route Table " + BOGUS_ROUTE_TABLE + " was deleted or does not exist.";
        refused(call("GetTransitGatewayRouteTableAssociations", "TransitGatewayRouteTableId", BOGUS_ROUTE_TABLE),
                "InvalidRouteTableID.NotFound", message);
        refused(call("GetTransitGatewayRouteTablePropagations", "TransitGatewayRouteTableId", BOGUS_ROUTE_TABLE),
                "InvalidRouteTableID.NotFound", message);
    }

    @Test
    void vpcAttachmentModifyAndDeleteNameTheAttachmentAsAVpcAttachment() {
        String message = "Transit Gateway VPC Attachment " + BOGUS_ATTACHMENT + " was deleted or does not exist.";
        refused(call("ModifyTransitGatewayVpcAttachment", "TransitGatewayAttachmentId", BOGUS_ATTACHMENT),
                "InvalidTransitGatewayAttachmentID.NotFound", message);
        refused(call("DeleteTransitGatewayVpcAttachment", "TransitGatewayAttachmentId", BOGUS_ATTACHMENT),
                "InvalidTransitGatewayAttachmentID.NotFound", message);
    }

    @Test
    void deletingARouteThatIsNotThereNamesTheTableAndLeavesItsRoutesAlone() {
        String gateway = createGateway();
        String routeTable = call("CreateTransitGatewayRouteTable", "TransitGatewayId", gateway).statusCode(200)
                .extract().path("CreateTransitGatewayRouteTableResponse.transitGatewayRouteTable"
                        + ".transitGatewayRouteTableId");
        call("CreateTransitGatewayRoute", "TransitGatewayRouteTableId", routeTable,
                "DestinationCidrBlock", "10.215.99.0/24", "Blackhole", "true").statusCode(200);

        refused(call("DeleteTransitGatewayRoute", "TransitGatewayRouteTableId", routeTable,
                        "DestinationCidrBlock", "192.0.2.0/24"),
                "InvalidRoute.NotFound",
                "Route 192.0.2.0/24 does not exists in Transit Gateway Route Table " + routeTable + ".");

        call("SearchTransitGatewayRoutes", "TransitGatewayRouteTableId", routeTable,
                "Filter.1.Name", "route-search.exact-match", "Filter.1.Value.1", "10.215.99.0/24")
                .statusCode(200)
                .body("SearchTransitGatewayRoutesResponse.routeSet.item.destinationCidrBlock", equalTo("10.215.99.0/24"));
    }
}
