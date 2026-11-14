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
        cognitoAction("DeleteUserPool", """
                {"UserPoolId":"%s"}
                """.formatted(poolId)).then().statusCode(200);
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
}
