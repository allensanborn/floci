package io.floci.aws.services.cognito;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.equalTo;

/**
 * The exact code, message and status Cognito answers for the refusals Floci used to word its own
 * way. Each test is one family, so that reverting that family's fix fails that test alone.
 */
@QuarkusTest
class CognitoErrorMessagesIntegrationTest {

    private static final String PASSWORD = "Perm1234!";

    private static String poolId;
    private static String clientId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createPoolClientAndUser() throws Exception {
        poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"error-messages"}
                """).path("UserPool").path("Id").asText();
        clientId = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"c","ExplicitAuthFlows":
                 ["ALLOW_USER_PASSWORD_AUTH","ALLOW_REFRESH_TOKEN_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        createConfirmedUser("alice");
    }

    @AfterEach
    void deletePool() {
        // a test may have deleted the pool already
        cognitoAction("DeleteUserPool", """
                {"UserPoolId":"%s"}
                """.formatted(poolId));
    }

    private static void createConfirmedUser(String username) {
        cognitoAction("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","MessageAction":"SUPPRESS"}
                """.formatted(poolId, username)).then().statusCode(200);
        cognitoAction("AdminSetUserPassword", """
                {"UserPoolId":"%s","Username":"%s","Password":"%s","Permanent":true}
                """.formatted(poolId, username, PASSWORD)).then().statusCode(200);
    }

    private static void assertError(Response response, String code, String message) {
        response.then().statusCode(400)
                .body("__type", equalTo(code))
                .body("message", equalTo(message));
    }

    @Test
    void unknownUserIsReportedAsUserDoesNotExist() {
        assertError(cognitoAction("AdminGetUser", """
                {"UserPoolId":"%s","Username":"nobody"}
                """.formatted(poolId)), "UserNotFoundException", "User does not exist.");
        assertError(cognitoAction("AdminDisableUser", """
                {"UserPoolId":"%s","Username":"nobody"}
                """.formatted(poolId)), "UserNotFoundException", "User does not exist.");

        createConfirmedUser("doomed");
        cognitoAction("AdminDeleteUser", """
                {"UserPoolId":"%s","Username":"doomed"}
                """.formatted(poolId)).then().statusCode(200);
        assertError(cognitoAction("AdminGetUser", """
                {"UserPoolId":"%s","Username":"doomed"}
                """.formatted(poolId)), "UserNotFoundException", "User does not exist.");
    }

    @Test
    void publicOperationsReportTheUsernameAndClientPairAsNotFound() {
        String pair = "Username/client id combination not found.";
        assertError(cognitoAction("ForgotPassword", """
                {"ClientId":"%s","Username":"nobody"}
                """.formatted(clientId)), "UserNotFoundException", pair);
        assertError(cognitoAction("ConfirmForgotPassword", """
                {"ClientId":"%s","Username":"nobody","ConfirmationCode":"000000","Password":"%s"}
                """.formatted(clientId, PASSWORD)), "UserNotFoundException", pair);
        assertError(cognitoAction("ConfirmSignUp", """
                {"ClientId":"%s","Username":"nobody","ConfirmationCode":"000000"}
                """.formatted(clientId)), "UserNotFoundException", pair);
        assertError(cognitoAction("ResendConfirmationCode", """
                {"ClientId":"%s","Username":"nobody"}
                """.formatted(clientId)), "UserNotFoundException", pair);
    }

    @Test
    void wrongPasswordCarriesTheTrailingPeriod() {
        String message = "Incorrect username or password.";
        assertError(cognitoAction("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"alice","PASSWORD":"wrong"}}
                """.formatted(clientId)), "NotAuthorizedException", message);
    }

    @Test
    void invalidTokensAreCapitalised() {
        assertError(cognitoAction("GetUser", """
                {"AccessToken":"not-a-token"}
                """), "NotAuthorizedException", "Invalid Access Token");
        assertError(cognitoAction("GetTokensFromRefreshToken", """
                {"ClientId":"%s","RefreshToken":"not-a-token"}
                """.formatted(clientId)), "NotAuthorizedException", "Invalid Refresh Token");
    }

    @Test
    void unknownGroupDoesNotNameTheGroup() {
        assertError(cognitoAction("GetGroup", """
                {"UserPoolId":"%s","GroupName":"missing"}
                """.formatted(poolId)), "ResourceNotFoundException", "Group not found.");
        assertError(cognitoAction("AdminAddUserToGroup", """
                {"UserPoolId":"%s","Username":"alice","GroupName":"missing"}
                """.formatted(poolId)), "ResourceNotFoundException", "Group not found.");
    }

    @Test
    void missingClientIsWordedPerOperation() throws Exception {
        String otherPool = cognitoJson("CreateUserPool", """
                {"PoolName":"other-pool"}
                """).path("UserPool").path("Id").asText();
        try {
            String doomed = cognitoJson("CreateUserPoolClient", """
                    {"UserPoolId":"%s","ClientName":"doomed"}
                    """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
            cognitoAction("DeleteUserPoolClient", """
                    {"UserPoolId":"%s","ClientId":"%s"}
                    """.formatted(poolId, doomed)).then().statusCode(200);

            String notFound = "ResourceNotFoundException";
            assertError(cognitoAction("DescribeUserPoolClient", """
                    {"UserPoolId":"%s","ClientId":"%s"}
                    """.formatted(poolId, doomed)), notFound, "User pool client does not exist.");
            assertError(cognitoAction("UpdateUserPoolClient", """
                    {"UserPoolId":"%s","ClientId":"%s","ClientName":"x"}
                    """.formatted(poolId, doomed)), notFound, "User pool client does not exist.");
            assertError(cognitoAction("DeleteUserPoolClient", """
                    {"UserPoolId":"%s","ClientId":"%s"}
                    """.formatted(poolId, doomed)), notFound, "User pool client " + doomed + " does not exist.");
            assertError(cognitoAction("InitiateAuth", """
                    {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                     "AuthParameters":{"USERNAME":"alice","PASSWORD":"x"}}
                    """.formatted(doomed)), notFound, "User pool client " + doomed + " does not exist.");
            assertError(cognitoAction("DescribeUserPoolClient", """
                    {"UserPoolId":"%s","ClientId":"%s"}
                    """.formatted(otherPool, clientId)), notFound,
                    "User pool client " + clientId + " does not exist in user pool " + otherPool + ".");
        } finally {
            cognitoAction("DeleteUserPool", """
                    {"UserPoolId":"%s"}
                    """.formatted(otherPool)).then().statusCode(200);
        }
        cognitoAction("DeleteUserPool", """
                {"UserPoolId":"%s"}
                """.formatted(poolId));
        assertError(cognitoAction("DescribeUserPoolClient", """
                {"UserPoolId":"%s","ClientId":"%s"}
                """.formatted(poolId, clientId)), "ResourceNotFoundException",
                "User pool " + poolId + " does not exist.");
    }

    @Test
    void updatePoolAndCreateClientRefuseAMissingPoolWithoutAMessage() {
        String gone = "us-east-1_doesnotexist";
        assertError(cognitoAction("UpdateUserPool", """
                {"UserPoolId":"%s","DeletionProtection":"INACTIVE"}
                """.formatted(gone)), "ResourceNotFoundException", "");
        assertError(cognitoAction("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"c"}
                """.formatted(gone)), "ResourceNotFoundException", "");
        // every other pool operation still says which pool
        assertError(cognitoAction("DescribeUserPool", """
                {"UserPoolId":"%s"}
                """.formatted(gone)), "ResourceNotFoundException", "User pool " + gone + " does not exist.");
    }

    @Test
    void takenDomainIsDomainAlreadyExists() {
        String domain = "error-messages-" + poolId.substring(poolId.indexOf('_') + 1).toLowerCase();
        cognitoAction("CreateUserPoolDomain", """
                {"UserPoolId":"%s","Domain":"%s"}
                """.formatted(poolId, domain)).then().statusCode(200);
        assertError(cognitoAction("CreateUserPoolDomain", """
                {"UserPoolId":"%s","Domain":"%s"}
                """.formatted(poolId, domain)), "InvalidParameterException", "Domain already exists.");
        cognitoAction("DescribeUserPoolDomain", """
                {"Domain":"%s"}
                """.formatted(domain)).then().statusCode(200)
                .body("DomainDescription.UserPoolId", equalTo(poolId));
        cognitoAction("DeleteUserPoolDomain", """
                {"UserPoolId":"%s","Domain":"%s"}
                """.formatted(poolId, domain)).then().statusCode(200);
    }

    @Test
    void adminInitiateAuthDoesNotNameTheDisabledFlow() {
        assertError(cognitoAction("AdminInitiateAuth", """
                {"UserPoolId":"%s","ClientId":"%s","AuthFlow":"ADMIN_USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"alice","PASSWORD":"%s"}}
                """.formatted(poolId, clientId, PASSWORD)),
                "InvalidParameterException", "Auth flow not enabled for this client");
        // InitiateAuth words the same refusal with the flow
        assertError(cognitoAction("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"CUSTOM_AUTH","AuthParameters":{"USERNAME":"alice"}}
                """.formatted(clientId)),
                "InvalidParameterException", "CUSTOM_AUTH flow not enabled for this client");
    }
}
