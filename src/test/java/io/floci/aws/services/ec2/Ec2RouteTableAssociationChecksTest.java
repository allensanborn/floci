package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * AssociateRouteTable refuses a subnet from another VPC, and DisassociateRouteTable refuses the
 * main association, both with InvalidParameterValue.
 */
@QuarkusTest
class Ec2RouteTableAssociationChecksTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static io.restassured.path.xml.XmlPath call(int status, String... params) {
        var req = given().header("Authorization", AUTH_HEADER).formParam("Action", params[0]);
        for (int i = 1; i < params.length; i += 2) {
            req = req.formParam(params[i], params[i + 1]);
        }
        return req.post("/").then().statusCode(status).extract().xmlPath();
    }

    @Test
    void crossVpcAssociationRejectedAndMainAssociationImmutable() {
        String vpcA = call(200, "CreateVpc", "CidrBlock", "10.201.0.0/16").getString("CreateVpcResponse.vpc.vpcId");
        String vpcB = call(200, "CreateVpc", "CidrBlock", "10.202.0.0/16").getString("CreateVpcResponse.vpc.vpcId");
        String subnetA = call(200, "CreateSubnet", "VpcId", vpcA, "CidrBlock", "10.201.1.0/24")
                .getString("CreateSubnetResponse.subnet.subnetId");
        String subnetB = call(200, "CreateSubnet", "VpcId", vpcB, "CidrBlock", "10.202.1.0/24")
                .getString("CreateSubnetResponse.subnet.subnetId");
        String rtb = call(200, "CreateRouteTable", "VpcId", vpcA)
                .getString("CreateRouteTableResponse.routeTable.routeTableId");

        call(200, "AssociateRouteTable", "RouteTableId", rtb, "SubnetId", subnetA);
        var err = call(400, "AssociateRouteTable", "RouteTableId", rtb, "SubnetId", subnetB);
        org.junit.jupiter.api.Assertions.assertEquals("InvalidParameterValue", err.getString("Response.Errors.Error.Code"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "route table " + rtb + " and subnet " + subnetB + " belong to different networks",
                err.getString("Response.Errors.Error.Message"));
        // read back: only the same-VPC association exists
        var rt = call(200, "DescribeRouteTables", "RouteTableId.1", rtb);
        org.junit.jupiter.api.Assertions.assertEquals(subnetA,
                rt.getString("DescribeRouteTablesResponse.routeTableSet.item.associationSet.item.subnetId"));

        var vpcRts = call(200, "DescribeRouteTables", "Filter.1.Name", "vpc-id", "Filter.1.Value.1", vpcA,
                "Filter.2.Name", "association.main", "Filter.2.Value.1", "true");
        String mainAssoc = vpcRts.getString(
                "DescribeRouteTablesResponse.routeTableSet.item.associationSet.item.routeTableAssociationId");
        var err2 = call(400, "DisassociateRouteTable", "AssociationId", mainAssoc);
        org.junit.jupiter.api.Assertions.assertEquals("InvalidParameterValue", err2.getString("Response.Errors.Error.Code"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "cannot disassociate the main route table association " + mainAssoc,
                err2.getString("Response.Errors.Error.Message"));
        call(200, "DescribeRouteTables", "Filter.1.Name", "association.route-table-association-id",
                "Filter.1.Value.1", mainAssoc);
    }
}
