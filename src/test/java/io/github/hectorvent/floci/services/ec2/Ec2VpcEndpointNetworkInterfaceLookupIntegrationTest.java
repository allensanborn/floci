package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An interface endpoint's network interfaces must RESOLVE, not merely be named.
 *
 * <p>DescribeVpcEndpoints publishes one ENI id per subnet in {@code networkInterfaceIdSet}.
 * Until this change DescribeNetworkInterfaces enumerated instance-attached interfaces and the
 * standalone {@code CreateNetworkInterface} store only, never
 * {@code Ec2Service.endpointNetworkInterfaces}, so asking about a published id got
 * {@code InvalidNetworkInterfaceID.NotFound} from the same emulator that had just handed it
 * over.
 *
 * <p>THE BLAST RADIUS IS NOT "callers who look up an ENI". The Terraform AWS provider does the
 * lookup ITSELF, inside its own read of {@code aws_vpc_endpoint}:
 * {@code resourceVPCEndpointFlatten} calls
 * {@code findSubnetConfigurationsByNetworkInterfaceIDs(ctx, conn, vpce.NetworkInterfaceIds)} over
 * every published id to build {@code subnet_configuration}, and returns the lookup error rather
 * than tolerating a NotFound -- it handles {@code retry.NotFound} for the prefix list a few lines
 * above and pointedly does not here. So publishing ids that do not resolve makes every interface
 * endpoint read FAIL, where publishing none had merely left the list empty. Reported as blocking
 * on floci-io/floci#4598; tracked as floci-5wby.
 *
 * <p>The round-trip test below walks exactly that path -- read the ids off a DescribeVpcEndpoints
 * response, then describe each one -- because that is the call sequence that breaks, and a test
 * that only asked about an id it derived itself would not pin the two sides together.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeNetworkInterfaces.html">DescribeNetworkInterfaces</a>
 * @see Ec2VpcEndpointNetworkInterfaceIdsIntegrationTest
 */
@QuarkusTest
class Ec2VpcEndpointNetworkInterfaceLookupIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String ENI_IDS =
            "DescribeVpcEndpointsResponse.vpcEndpointSet.item.networkInterfaceIdSet.item";
    private static final String NI_SET =
            "DescribeNetworkInterfacesResponse.networkInterfaceSet.item";

    private RequestSpecification ec2(String action, String... formParams) {
        RequestSpecification req = given().formParam("Action", action)
                .header("Authorization", AUTH_HEADER);
        for (int i = 0; i < formParams.length; i += 2) {
            req = req.formParam(formParams[i], formParams[i + 1]);
        }
        return req;
    }

    private String ec2Value(String action, String element, String... formParams) {
        return ec2(action, formParams).when().post("/").then().statusCode(200).extract().path(element);
    }

    private XmlPath ec2Xml(String action, String... formParams) {
        return ec2(action, formParams).when().post("/").then().statusCode(200).extract().xmlPath();
    }

    /** An interface endpoint across the given subnets, and the ids it publishes. */
    private String createInterfaceEndpoint(String vpcId, String... subnetIds) {
        String[] params = new String[6 + subnetIds.length * 2];
        params[0] = "VpcId";
        params[1] = vpcId;
        params[2] = "ServiceName";
        params[3] = "com.amazonaws.us-east-1.ec2";
        params[4] = "VpcEndpointType";
        params[5] = "Interface";
        for (int i = 0; i < subnetIds.length; i++) {
            params[6 + i * 2] = "SubnetId." + (i + 1);
            params[7 + i * 2] = subnetIds[i];
        }
        return ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId", params);
    }

    private String createVpc(String cidr) {
        return ec2Value("CreateVpc", "CreateVpcResponse.vpc.vpcId", "CidrBlock", cidr);
    }

    private String createSubnet(String vpcId, String cidr, String az) {
        return ec2Value("CreateSubnet", "CreateSubnetResponse.subnet.subnetId",
                "VpcId", vpcId, "CidrBlock", cidr, "AvailabilityZone", az);
    }

    private List<String> publishedEniIds(String endpointId) {
        return ec2Xml("DescribeVpcEndpoints", "VpcEndpointId.1", endpointId)
                .getList(ENI_IDS, String.class);
    }

    @Test
    void anEndpointInterfaceResolvesWhenAskedForById() {
        // THE MINIMUM THE MAINTAINER ASKED FOR: read an id back. Before the change this call
        // answered 400 InvalidNetworkInterfaceID.NotFound.
        String vpcId = createVpc("10.84.0.0/16");
        String subnetId = createSubnet(vpcId, "10.84.1.0/24", "us-east-1a");
        String endpointId = createInterfaceEndpoint(vpcId, subnetId);

        List<String> published = publishedEniIds(endpointId);
        assertEquals(1, published.size(), "one subnet, so one published id");
        String eniId = published.get(0);

        XmlPath answer = ec2Xml("DescribeNetworkInterfaces", "NetworkInterfaceId.1", eniId);
        assertEquals(List.of(eniId), answer.getList(NI_SET + ".networkInterfaceId", String.class));
        assertEquals(subnetId, answer.getString(NI_SET + ".subnetId"));
        assertEquals(vpcId, answer.getString(NI_SET + ".vpcId"));
    }

    @Test
    void theProviderRoundTripResolvesEveryPublishedId() {
        // The provider's own sequence: read network_interface_ids off the endpoint, then look up
        // each one to build subnet_configuration. It reads PrivateIpAddress, Ipv6Address and
        // SubnetId off each answer, so an interface that resolves but carries no subnet would
        // still leave the provider with a subnet_configuration entry naming nothing.
        String vpcId = createVpc("10.85.0.0/16");
        String subnetA = createSubnet(vpcId, "10.85.1.0/24", "us-east-1a");
        String subnetB = createSubnet(vpcId, "10.85.2.0/24", "us-east-1b");
        String endpointId = createInterfaceEndpoint(vpcId, subnetA, subnetB);

        List<String> published = publishedEniIds(endpointId);
        assertEquals(2, published.size(), "one interface per subnet, got " + published);

        for (String eniId : published) {
            XmlPath answer = ec2Xml("DescribeNetworkInterfaces", "NetworkInterfaceId.1", eniId);
            assertEquals(eniId, answer.getString(NI_SET + ".networkInterfaceId"),
                    "a published id must describe as itself");
            String subnetId = answer.getString(NI_SET + ".subnetId");
            assertTrue(subnetA.equals(subnetId) || subnetB.equals(subnetId),
                    "the interface must name one of the endpoint's subnets, got " + subnetId);
            assertFalse(answer.getString(NI_SET + ".privateIpAddress").isEmpty(),
                    "subnet_configuration is built from the private address, which must be there");
        }

        // AND ALL AT ONCE, which is the form the provider's loop amounts to: no id in the batch
        // may be the one that throws.
        XmlPath both = ec2Xml("DescribeNetworkInterfaces",
                "NetworkInterfaceId.1", published.get(0),
                "NetworkInterfaceId.2", published.get(1));
        assertEquals(published, both.getList(NI_SET + ".networkInterfaceId", String.class));
    }

    @Test
    void anEndpointInterfaceIsListedWithNoFilter() {
        String vpcId = createVpc("10.86.0.0/16");
        String subnetId = createSubnet(vpcId, "10.86.1.0/24", "us-east-1a");
        String endpointId = createInterfaceEndpoint(vpcId, subnetId);
        String eniId = publishedEniIds(endpointId).get(0);

        // Asserted by membership, not by count: this class shares its Quarkus application with
        // every other EC2 test, so the unfiltered list holds whatever they left behind.
        List<String> all = ec2Xml("DescribeNetworkInterfaces")
                .getList(NI_SET + ".networkInterfaceId", String.class);
        assertThat(all, hasItem(eniId));
    }

    @Test
    void theWireShapeIsAnAwsManagedVpcEndpointInterface() {
        String vpcId = createVpc("10.87.0.0/16");
        String subnetId = createSubnet(vpcId, "10.87.1.0/24", "us-east-1c");
        String endpointId = createInterfaceEndpoint(vpcId, subnetId);
        String eniId = publishedEniIds(endpointId).get(0);

        XmlPath answer = ec2Xml("DescribeNetworkInterfaces", "NetworkInterfaceId.1", eniId);
        assertEquals("vpc_endpoint", answer.getString(NI_SET + ".interfaceType"),
                "the documented interfaceType for an interface endpoint's ENI");
        assertEquals("true", answer.getString(NI_SET + ".requesterManaged"),
                "AWS creates these on the customer's behalf and reports them as requester-managed");
        assertEquals("in-use", answer.getString(NI_SET + ".status"));
        assertEquals("us-east-1c", answer.getString(NI_SET + ".availabilityZone"));
        assertEquals("VPC Endpoint Interface " + endpointId,
                answer.getString(NI_SET + ".description"));
    }

    @Test
    void filtersSelectEndpointInterfaces() {
        String vpcId = createVpc("10.88.0.0/16");
        String subnetA = createSubnet(vpcId, "10.88.1.0/24", "us-east-1a");
        String subnetB = createSubnet(vpcId, "10.88.2.0/24", "us-east-1b");
        String endpointId = createInterfaceEndpoint(vpcId, subnetA, subnetB);
        List<String> published = publishedEniIds(endpointId);

        // vpc-id: exactly this endpoint's two interfaces live in this brand-new VPC.
        assertEquals(published, filteredIds("vpc-id", vpcId));

        // subnet-id: one each.
        assertEquals(1, filteredIds("subnet-id", subnetA).size());
        assertEquals(1, filteredIds("subnet-id", subnetB).size());
        assertThat(filteredIds("subnet-id", subnetA), hasItem(published.get(0)));

        // interface-type: scoped to this VPC so other tests' endpoints do not enter the
        // assertion, and paired with its negative -- an arm returning true for everything
        // would satisfy the positive alone.
        assertThat(filteredIds("vpc-id", vpcId, "interface-type", "vpc_endpoint"),
                hasItem(published.get(0)));
        assertThat(filteredIds("vpc-id", vpcId, "interface-type", "interface"),
                not(hasItem(published.get(0))));

        // requester-managed, the same way. Asserted as equality rather than with everyItem,
        // which an empty answer would satisfy vacuously -- and an empty answer is exactly the
        // failure mode being guarded against.
        assertEquals(published, filteredIds("vpc-id", vpcId, "requester-managed", "true"));
        assertTrue(filteredIds("vpc-id", vpcId, "requester-managed", "false").isEmpty(),
                "nothing in this VPC is customer-managed");
    }

    @Test
    void anIdThatWasNeverPublishedStillDoesNotExist() {
        // GUARD on the validation sweep. Teaching the lookup about endpoint interfaces must not
        // turn InvalidNetworkInterfaceID.NotFound into something that never fires -- a store
        // that resolves everything is as wrong as one that resolves nothing, and the tests above
        // would pass against it.
        ec2("DescribeNetworkInterfaces", "NetworkInterfaceId.1", "eni-00000000000000000")
                .when().post("/")
                .then().statusCode(400)
                .body("Response.Errors.Error.Code", org.hamcrest.Matchers.equalTo(
                        "InvalidNetworkInterfaceID.NotFound"));
    }

    private List<String> filteredIds(String... nameValuePairs) {
        String[] params = new String[nameValuePairs.length * 2];
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            int n = i / 2 + 1;
            params[i * 2] = "Filter." + n + ".Name";
            params[i * 2 + 1] = nameValuePairs[i];
            params[i * 2 + 2] = "Filter." + n + ".Value.1";
            params[i * 2 + 3] = nameValuePairs[i + 1];
        }
        return ec2Xml("DescribeNetworkInterfaces", params)
                .getList(NI_SET + ".networkInterfaceId", String.class);
    }
}
