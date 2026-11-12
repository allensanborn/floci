package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AssociateRouteTable refuses a subnet from another VPC, and DisassociateRouteTable refuses the
 * main association, both with InvalidParameterValue.
 */
@QuarkusTest
class Ec2RouteTableAssociationChecksIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static XmlPath call(int status, String... params) {
        RequestSpecification req = given().header("Authorization", AUTH_HEADER).formParam("Action", params[0]);
        for (int i = 1; i < params.length; i += 2) {
            req = req.formParam(params[i], params[i + 1]);
        }
        return req.post("/").then().statusCode(status).extract().xmlPath();
    }

    @Test
    void crossVpcAssociationRejected() {
        String vpcA = call(200, "CreateVpc", "CidrBlock", "10.201.0.0/16").getString("CreateVpcResponse.vpc.vpcId");
        String vpcB = call(200, "CreateVpc", "CidrBlock", "10.202.0.0/16").getString("CreateVpcResponse.vpc.vpcId");
        String subnetA = call(200, "CreateSubnet", "VpcId", vpcA, "CidrBlock", "10.201.1.0/24")
                .getString("CreateSubnetResponse.subnet.subnetId");
        String subnetB = call(200, "CreateSubnet", "VpcId", vpcB, "CidrBlock", "10.202.1.0/24")
                .getString("CreateSubnetResponse.subnet.subnetId");
        String rtb = call(200, "CreateRouteTable", "VpcId", vpcA)
                .getString("CreateRouteTableResponse.routeTable.routeTableId");

        call(200, "AssociateRouteTable", "RouteTableId", rtb, "SubnetId", subnetA);
        XmlPath err = call(400, "AssociateRouteTable", "RouteTableId", rtb, "SubnetId", subnetB);
        assertEquals("InvalidParameterValue", err.getString("Response.Errors.Error.Code"));
        assertEquals("route table " + rtb + " and subnet " + subnetB + " belong to different networks",
                err.getString("Response.Errors.Error.Message"));
        // read back: only the same-VPC association exists
        XmlPath rt = call(200, "DescribeRouteTables", "RouteTableId.1", rtb);
        assertEquals(subnetA,
                rt.getString("DescribeRouteTablesResponse.routeTableSet.item.associationSet.item.subnetId"));
    }

    @Test
    void mainAssociationCannotBeDisassociated() {
        String vpc = call(200, "CreateVpc", "CidrBlock", "10.203.0.0/16").getString("CreateVpcResponse.vpc.vpcId");
        String mainAssoc = call(200, "DescribeRouteTables", "Filter.1.Name", "vpc-id", "Filter.1.Value.1", vpc,
                "Filter.2.Name", "association.main", "Filter.2.Value.1", "true")
                .getString("DescribeRouteTablesResponse.routeTableSet.item.associationSet.item.routeTableAssociationId");
        XmlPath err = call(400, "DisassociateRouteTable", "AssociationId", mainAssoc);
        assertEquals("InvalidParameterValue", err.getString("Response.Errors.Error.Code"));
        assertEquals("cannot disassociate the main route table association " + mainAssoc,
                err.getString("Response.Errors.Error.Message"));
        // read back: the main association is still there
        assertEquals(mainAssoc, call(200, "DescribeRouteTables", "Filter.1.Name", "vpc-id", "Filter.1.Value.1", vpc,
                "Filter.2.Name", "association.main", "Filter.2.Value.1", "true")
                .getString("DescribeRouteTablesResponse.routeTableSet.item.associationSet.item.routeTableAssociationId"));
    }
}
