package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import jakarta.inject.Inject;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code networkInterfaceIdSet} an interface endpoint reports.
 *
 * <p>AWS creates one elastic network interface per subnet for an interface (PrivateLink)
 * endpoint and reports them here. Terraform's {@code aws_vpc_endpoint} surfaces the list as
 * {@code network_interface_ids}, and modules feed that output downstream -- to security-group
 * rules, to flow-log filters, to DNS -- so an empty list does not merely diff against AWS, it
 * propagates into whatever consumes it.
 *
 * <p>Floci already synthesized these interfaces: {@code Ec2Service.endpointNetworkInterfaces}
 * derives them deterministically from the endpoint's subnets so flow-log generation can
 * attribute AWS-service traffic to a stable address. Nothing reported them on the wire, so
 * DescribeVpcEndpoints answered with the element absent.
 *
 * <p>A Gateway endpoint has no interfaces, and AWS reports none for one; that direction is
 * asserted too, because a change that simply always emitted the set would otherwise pass.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeVpcEndpoints.html">DescribeVpcEndpoints</a>
 */
@QuarkusTest
class Ec2VpcEndpointNetworkInterfaceIdsIntegrationTest {

    @Inject
    Ec2Service service;

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String ENI_IDS =
            "DescribeVpcEndpointsResponse.vpcEndpointSet.item.networkInterfaceIdSet.item";

    private String ec2Value(String action, String element, String... formParams) {
        RequestSpecification req = given().formParam("Action", action)
                .header("Authorization", AUTH_HEADER);
        for (int i = 0; i < formParams.length; i += 2) {
            req = req.formParam(formParams[i], formParams[i + 1]);
        }
        return req.when().post("/").then().statusCode(200).extract().path(element);
    }

    private String createVpc(String cidr) {
        return ec2Value("CreateVpc", "CreateVpcResponse.vpc.vpcId", "CidrBlock", cidr);
    }

    private String createSubnet(String vpcId, String cidr, String availabilityZone) {
        return ec2Value("CreateSubnet", "CreateSubnetResponse.subnet.subnetId",
                "VpcId", vpcId, "CidrBlock", cidr, "AvailabilityZone", availabilityZone);
    }

    private XmlPath describeEndpoint(String endpointId) {
        return given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", endpointId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().xmlPath();
    }

    private List<String> eniIdsOf(String endpointId) {
        return describeEndpoint(endpointId).getList(ENI_IDS, String.class);
    }

    @Test
    void anInterfaceEndpointReportsOneNetworkInterfacePerSubnet() {
        String vpcId = createVpc("10.74.0.0/16");
        String subnetA = createSubnet(vpcId, "10.74.1.0/24", "us-east-1a");
        String subnetB = createSubnet(vpcId, "10.74.2.0/24", "us-east-1b");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.ec2",
                "VpcEndpointType", "Interface", "SubnetId.1", subnetA, "SubnetId.2", subnetB);

        List<String> eniIds = eniIdsOf(endpointId);
        assertEquals(2, eniIds.size(), "one interface per subnet, got " + eniIds);
        assertThat(eniIds, everyItem(matchesPattern("eni-[0-9a-f]{17}")));
        assertNotEquals(eniIds.get(0), eniIds.get(1),
                "each subnet gets its own interface, not one shared id");

        // Stable across calls. The ids are derived, not stored, so a caller that reads them
        // twice -- as Terraform does between plan and apply -- must see the same list.
        assertEquals(eniIds, eniIdsOf(endpointId));
    }

    @Test
    void theWireIdsAreTheInterfacesFlowLogAttributionReads() {
        // THE PROPERTY THE DESIGN RESTS ON, and the one the other tests do not reach.
        // They pin count, shape, distinctness and stability -- all of which an
        // implementation deriving ids from the subnet id alone would satisfy while
        // reporting interfaces that endpointNetworkInterfaces() denies exist. That is
        // not hypothetical: an earlier version of endpointNetworkInterfaceIds called
        // endpointEniId itself, lacked this method's skip of a vanished subnet, and the
        // two were measured reporting different sets after a subnet was deleted out from
        // under a live endpoint. Flow-log attribution reads the service side; Terraform
        // reads the wire side; they have to be the same list.
        String vpcId = createVpc("10.77.0.0/16");
        String subnetA = createSubnet(vpcId, "10.77.1.0/24", "us-east-1a");
        String subnetB = createSubnet(vpcId, "10.77.2.0/24", "us-east-1b");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.ec2",
                "VpcEndpointType", "Interface", "SubnetId.1", subnetA, "SubnetId.2", subnetB);

        // DELETE A SUBNET OUT FROM UNDER THE LIVE ENDPOINT. Without this the test is
        // decorative: both derivations agree whenever every subnet still exists, so a
        // version that skipped the vanished-subnet filter passed this assertion happily.
        // Measured -- the first draft of this test did exactly that. Real AWS answers
        // DependencyViolation here and Terraform's graph destroys the endpoint first, so
        // the state is reachable only in an emulator; that is precisely why the emulator
        // has to stay self-consistent in it rather than report two different answers.
        ec2Value("DeleteSubnet", "DeleteSubnetResponse.return", "SubnetId", subnetB);

        List<String> onTheWire = eniIdsOf(endpointId);

        // Scoped to THIS endpoint: the region-wide overload returns every endpoint's
        // interfaces, and other tests in this class leave their own behind.
        List<String> fromTheService = new ArrayList<>();
        for (NetworkInterface ni : service.endpointNetworkInterfaces("us-east-1")) {
            if (("VPC Endpoint Interface " + endpointId).equals(ni.getDescription())) {
                fromTheService.add(ni.getNetworkInterfaceId());
            }
        }

        assertEquals(fromTheService, onTheWire,
                "the ids DescribeVpcEndpoints publishes must be the interfaces the "
                + "service reports, in the same order");
        assertEquals(1, onTheWire.size(),
                "the endpoint has one surviving subnet, so it reports one interface");
    }

    @Test
    void aGatewayEndpointReportsNoNetworkInterfaces() {
        String vpcId = createVpc("10.75.0.0/16");
        String routeTableId = ec2Value("CreateRouteTable",
                "CreateRouteTableResponse.routeTable.routeTableId", "VpcId", vpcId);

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.s3",
                "VpcEndpointType", "Gateway", "RouteTableId.1", routeTableId);

        // A gateway endpoint is a route-table entry, not an ENI. Asserted so that emitting
        // the set unconditionally would not pass this suite.
        assertTrue(eniIdsOf(endpointId).isEmpty(),
                "a gateway endpoint owns no interfaces");
    }

    @Test
    void anInterfaceEndpointInOneSubnetReportsExactlyOneInterface() {
        String vpcId = createVpc("10.76.0.0/16");
        String subnetId = createSubnet(vpcId, "10.76.1.0/24", "us-east-1a");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.ecr.api",
                "VpcEndpointType", "Interface", "SubnetId.1", subnetId);

        assertEquals(1, eniIdsOf(endpointId).size());
    }
}
