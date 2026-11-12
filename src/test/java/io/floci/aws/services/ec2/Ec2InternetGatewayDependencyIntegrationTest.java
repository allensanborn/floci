package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Attach, detach and delete of an internet gateway refuse what AWS refuses, with AWS's codes and
 * messages, and a refused call changes nothing (read back through DescribeInternetGateways).
 */
@QuarkusTest
class Ec2InternetGatewayDependencyIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static RequestSpecification ec2() {
        return given().header("Authorization", AUTH_HEADER);
    }

    private static String create(String action, String cidr, String idPath) {
        RequestSpecification r = ec2().formParam("Action", action);
        if (cidr != null) {
            r = r.formParam("CidrBlock", cidr);
        }
        return r.when().post("/").then().statusCode(200).extract().xmlPath().getString(idPath);
    }

    private static String vpc(String cidr) {
        return create("CreateVpc", cidr, "CreateVpcResponse.vpc.vpcId");
    }

    private static String igw() {
        return create("CreateInternetGateway", null, "CreateInternetGatewayResponse.internetGateway.internetGatewayId");
    }

    private static void call(String action, String igwId, String vpcId, int status, String code, String message) {
        RequestSpecification r = ec2().formParam("Action", action).formParam("InternetGatewayId", igwId);
        if (vpcId != null) {
            r = r.formParam("VpcId", vpcId);
        }
        var resp = r.when().post("/").then().statusCode(status);
        if (code != null) {
            resp.body("Response.Errors.Error.Code", equalTo(code))
                .body("Response.Errors.Error.Message", equalTo(message));
        }
    }

    private static String attachedVpc(String igwId) {
        return ec2().formParam("Action", "DescribeInternetGateways").formParam("InternetGatewayId.1", igwId)
                .when().post("/").then().statusCode(200).extract().xmlPath()
                .getString("DescribeInternetGatewaysResponse.internetGatewaySet.item.attachmentSet.item.vpcId");
    }

    @Test
    void attachRefusesAnAttachedGatewayAndAVpcThatAlreadyHasOne() {
        String vpc1 = vpc("198.51.100.0/24");
        String vpc2 = vpc("198.51.101.0/24");
        String first = igw();
        String second = igw();
        call("AttachInternetGateway", first, vpc1, 200, null, null);

        call("AttachInternetGateway", second, vpc1, 400, "InvalidParameterValue",
                "Network " + vpc1 + " already has an internet gateway attached");
        call("AttachInternetGateway", first, vpc2, 400, "Resource.AlreadyAssociated",
                "resource " + first + " is already attached to network " + vpc1);

        assertEquals(vpc1, attachedVpc(first));
        assertEquals("", attachedVpc(second));
    }

    @Test
    void detachNamingTheWrongVpcOrADetachedGatewayIsRefused() {
        String vpc1 = vpc("198.51.102.0/24");
        String vpc2 = vpc("198.51.103.0/24");
        String gw = igw();
        call("AttachInternetGateway", gw, vpc1, 200, null, null);

        call("DetachInternetGateway", gw, vpc2, 400, "Gateway.NotAttached",
                "resource " + gw + " is not attached to network " + vpc2);
        assertEquals(vpc1, attachedVpc(gw));

        call("DetachInternetGateway", gw, vpc1, 200, null, null);
        call("DetachInternetGateway", gw, vpc1, 400, "Gateway.NotAttached",
                "resource " + gw + " is not attached to network " + vpc1);
    }

    @Test
    void deleteRefusesAnAttachedGatewayAndAZeroedId() {
        String vpc1 = vpc("198.51.104.0/24");
        String gw = igw();
        call("AttachInternetGateway", gw, vpc1, 200, null, null);

        call("DeleteInternetGateway", gw, null, 400, "DependencyViolation",
                "The internetGateway '" + gw + "' has dependencies and cannot be deleted.");
        assertEquals(vpc1, attachedVpc(gw));

        call("DetachInternetGateway", gw, vpc1, 200, null, null);
        call("DeleteInternetGateway", gw, null, 200, null, null);
        ec2().formParam("Action", "DescribeInternetGateways").formParam("InternetGatewayId.1", gw)
                .when().post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidInternetGatewayID.NotFound"))
                .body("Response.Errors.Error.Message", equalTo("The internetGateway ID '" + gw + "' does not exist"));

        call("DeleteInternetGateway", "igw-00000000", null, 400, "InvalidInternetGatewayId.Malformed",
                "The internet-gateway ID 'igw-00000000' is malformed");
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
