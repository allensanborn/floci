package io.github.hectorvent.floci.services.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.xml.HasXPath.hasXPath;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;

/**
 * A route is addressed by one destination, and per
 * https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_Route.html the Route type carries the
 * IPv4 and IPv6 destinations as separate members. CreateRoute used to accept
 * DestinationIpv6CidrBlock and drop it, storing a route with no destination at all: it could never
 * be matched (so a create-waiter polls to its deadline) and its null destination made every
 * DeleteRoute against the same table throw, IPv4 ones included.
 *
 * <p>The XPath assertions use {@code local-name()} because EC2 responses carry a default namespace.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2Ipv6RouteTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String VPC_CIDR = "203.0.113.0/24";
    private static final String IPV4_ROUTE = "0.0.0.0/0";
    private static final String IPV6_ROUTE = "::/0";
    private static final String INTERNET_GATEWAY = "igw-0ipv6route0test0";
    private static final String NAT_GATEWAY = "nat-0ipv6route0test0";
    private static final String ROUTE_SET =
            "DescribeRouteTablesResponse.routeTableSet.item.routeSet.item";
    private static final String IPV6_NODE =
            ROUTE_SET + ".find { it.destinationIpv6CidrBlock == '" + IPV6_ROUTE + "' }";
    private static final String IPV4_NODE =
            ROUTE_SET + ".find { it.destinationCidrBlock == '" + IPV4_ROUTE + "' }";

    private static String routeTableId;

    private static io.restassured.specification.RequestSpecification ec2() {
        return given().header("Authorization", AUTH_HEADER);
    }

    private static io.restassured.response.ValidatableResponse describe() {
        return ec2()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(1)
    void createARouteTableWithAnIpv4AndAnIpv6Route() {
        String vpcId = ec2()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", VPC_CIDR)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        routeTableId = ec2()
            .formParam("Action", "CreateRouteTable")
            .formParam("VpcId", vpcId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateRouteTableResponse.routeTable.routeTableId");

        ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", IPV4_ROUTE)
            .formParam("GatewayId", INTERNET_GATEWAY)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateRouteResponse.return", equalTo("true"));

        ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationIpv6CidrBlock", IPV6_ROUTE)
            .formParam("GatewayId", INTERNET_GATEWAY)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateRouteResponse.return", equalTo("true"));
    }

    /**
     * The failure this fixes: the IPv6 route came back with no destination element at all, so the
     * Terraform provider's create-waiter never saw the route it had just created.
     */
    @Test
    @Order(2)
    void anIpv6RouteReportsItsDestination() {
        String table = describe()
            .body(IPV6_NODE + ".gatewayId", equalTo(INTERNET_GATEWAY))
            .body(IPV6_NODE + ".state", equalTo("active"))
            .body(IPV6_NODE + ".origin", equalTo("CreateRoute"))
            .extract().asString();

        assertThat(table, containsString("<destinationIpv6CidrBlock>" + IPV6_ROUTE
                + "</destinationIpv6CidrBlock>"));
    }

    /** The IPv4 route is unchanged, and carries no IPv6 destination member. */
    @Test
    @Order(3)
    void anIpv4RouteStillReportsItsDestination() {
        describe()
            .body(IPV4_NODE + ".gatewayId", equalTo(INTERNET_GATEWAY))
            // ...and carries no IPv6 destination member: the two are separate members of Route,
            // not one field reused.
            .body(hasXPath("count(//*[local-name()='item']"
                    + "[*[local-name()='destinationCidrBlock']='" + IPV4_ROUTE + "']"
                    + "/*[local-name()='destinationIpv6CidrBlock'])", equalTo("0")));
    }

    /**
     * Both destinations live in one table as separate routes — three in total with the local route
     * CreateRouteTable inserts. A model that reused a single destination field would show two.
     */
    @Test
    @Order(4)
    void bothRoutesCoexistInTheSameTable() {
        describe()
            .body(hasXPath("count(//*[local-name()='routeSet']/*[local-name()='item'])",
                    equalTo("3")))
            .body(hasXPath("count(//*[local-name()='destinationIpv6CidrBlock'])", equalTo("1")))
            // The local route plus the IPv4 default route.
            .body(hasXPath("count(//*[local-name()='destinationCidrBlock'])", equalTo("2")));
    }

    /** DescribeRouteTables can be filtered on the IPv6 destination, and the filter discriminates. */
    @Test
    @Order(5)
    void routeTablesCanBeFilteredOnTheIpv6Destination() {
        String matching = ec2()
            .formParam("Action", "DescribeRouteTables")
            .formParam("Filter.1.Name", "route.destination-ipv6-cidr-block")
            .formParam("Filter.1.Value.1", IPV6_ROUTE)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(matching, containsString(routeTableId));

        // An unrelated IPv6 destination must not match: the branch used to fall through to
        // `default -> true`, so this filter matched every route table rather than none.
        String notMatching = ec2()
            .formParam("Action", "DescribeRouteTables")
            .formParam("Filter.1.Name", "route.destination-ipv6-cidr-block")
            .formParam("Filter.1.Value.1", "2001:db8::/32")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(notMatching, not(containsString(routeTableId)));
    }

    /** ReplaceRoute used to hard-reject any request without an IPv4 destination. */
    @Test
    @Order(6)
    void replaceRouteRepointsTheIpv6Route() {
        ec2()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationIpv6CidrBlock", IPV6_ROUTE)
            .formParam("NatGatewayId", NAT_GATEWAY)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ReplaceRouteResponse.return", equalTo("true"));

        describe()
            .body(IPV6_NODE + ".natGatewayId", equalTo(NAT_GATEWAY))
            // Replace, not merge: the gateway the route used to carry is gone...
            .body(hasXPath("count(//*[local-name()='item']"
                    + "[*[local-name()='destinationIpv6CidrBlock']='" + IPV6_ROUTE + "']"
                    + "/*[local-name()='gatewayId'])", equalTo("0")))
            // ...and only the addressed route moved.
            .body(IPV4_NODE + ".gatewayId", equalTo(INTERNET_GATEWAY));
    }

    /**
     * The regression that broke `terraform destroy` independently of the create failure: with an
     * IPv6 route in the table, deleting an IPv4 route dereferenced the IPv6 route's null IPv4
     * destination and returned InternalFailure.
     */
    @Test
    @Order(7)
    void deletingAnIpv4RouteWorksWhileAnIpv6RouteIsPresent() {
        ec2()
            .formParam("Action", "DeleteRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", IPV4_ROUTE)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteRouteResponse.return", equalTo("true"));

        describe()
            // The local route and the IPv6 route are left; the IPv4 default route is gone.
            .body(hasXPath("count(//*[local-name()='routeSet']/*[local-name()='item'])",
                    equalTo("2")))
            .body(hasXPath("count(//*[local-name()='destinationCidrBlock'][text()='"
                    + IPV4_ROUTE + "'])", equalTo("0")))
            // The IPv6 route is untouched.
            .body(IPV6_NODE + ".natGatewayId", equalTo(NAT_GATEWAY));
    }

    @Test
    @Order(8)
    void deletingTheIpv6RouteRemovesIt() {
        ec2()
            .formParam("Action", "DeleteRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationIpv6CidrBlock", IPV6_ROUTE)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteRouteResponse.return", equalTo("true"));

        describe()
            .body(hasXPath("count(//*[local-name()='destinationIpv6CidrBlock'])", equalTo("0")))
            // Only the local route CreateRouteTable inserted is left.
            .body(hasXPath("count(//*[local-name()='routeSet']/*[local-name()='item'])",
                    equalTo("1")));
    }

    /**
     * A prefix-list route arrives with neither destination. Storing it would put a route in the
     * table that nothing can ever address again — AWS answers MissingParameter instead.
     */
    @Test
    @Order(9)
    void createRouteWithNoDestinationIsRejected() {
        ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationPrefixListId", "pl-0ipv6route0test0")
            .formParam("GatewayId", INTERNET_GATEWAY)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));

        // ...and nothing was added to the table.
        describe()
            .body(hasXPath("count(//*[local-name()='routeSet']/*[local-name()='item'])",
                    equalTo("1")));
    }

    /** One destination per route: a request naming both would be addressable two ways. */
    @Test
    @Order(10)
    void createRouteWithBothDestinationsIsRejected() {
        ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", IPV4_ROUTE)
            .formParam("DestinationIpv6CidrBlock", IPV6_ROUTE)
            .formParam("GatewayId", INTERNET_GATEWAY)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
    }

    /**
     * The egress-only internet gateway actions are not implemented, but the id a caller sends is
     * stored and reported rather than dropped, so the route is not left targetless.
     */
    @Test
    @Order(11)
    void anEgressOnlyInternetGatewayTargetIsReportedBack() {
        String egressOnlyGateway = "eigw-0ipv6route0test";

        ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationIpv6CidrBlock", IPV6_ROUTE)
            .formParam("EgressOnlyInternetGatewayId", egressOnlyGateway)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateRouteResponse.return", equalTo("true"));

        describe()
            .body(IPV6_NODE + ".egressOnlyInternetGatewayId", equalTo(egressOnlyGateway));
    }
}
