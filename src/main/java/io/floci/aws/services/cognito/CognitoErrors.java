package io.floci.aws.services.cognito;

import io.floci.aws.core.common.AwsException;

/**
 * The error messages Cognito User Pools gives for refusals Floci used to word its own way. Each
 * constant or factory is the exact text AWS answers, so a caller that matches on it, or an SDK that
 * surfaces it, sees what a real pool sends; the codes and statuses are unchanged by living here.
 */
final class CognitoErrors {

    /** The refusal for a wrong password, which Cognito words alike whether or not the user exists. */
    static final String INCORRECT_CREDENTIALS = "Incorrect username or password.";

    /** A token that is no access token Cognito issued, malformed or from another pool. */
    static final String INVALID_ACCESS_TOKEN = "Invalid Access Token";

    /** A token that is no refresh token Cognito issued to this client. */
    static final String INVALID_REFRESH_TOKEN = "Invalid Refresh Token";

    private CognitoErrors() {
    }

    /** An unknown user: the same sentence whichever pool and username were asked for. */
    static AwsException userNotFound() {
        return new AwsException("UserNotFoundException", "User does not exist.", 400);
    }

    /**
     * An unknown user for the public operations that resolve it from {@code ClientId} and
     * {@code Username} together (ForgotPassword, ConfirmForgotPassword, ConfirmSignUp, ResendConfirmationCode).
     */
    static AwsException usernameClientNotFound() {
        return new AwsException("UserNotFoundException", "Username/client id combination not found.", 400);
    }

    /** An unknown group: the same sentence whichever name was asked for. */
    static AwsException groupNotFound() {
        return new AwsException("ResourceNotFoundException", "Group not found.", 400);
    }

    /** An app client that is not there, for the operations that address it by pool and id together. */
    static AwsException userPoolClientDoesNotExist() {
        return new AwsException("ResourceNotFoundException", "User pool client does not exist.", 400);
    }

    /** An app client that is not there, for the operations that name the id they were asked for. */
    static AwsException userPoolClientNotFound(String clientId) {
        return new AwsException("ResourceNotFoundException",
                "User pool client " + clientId + " does not exist.", 400);
    }

    /** An app client that exists, but in a pool other than the one the request named. */
    static AwsException userPoolClientNotInPool(String clientId, String userPoolId) {
        return new AwsException("ResourceNotFoundException",
                "User pool client " + clientId + " does not exist in user pool " + userPoolId + ".", 400);
    }

    /**
     * A missing pool, for UpdateUserPool and CreateUserPoolClient, which AWS refuses with the code
     * and status alone: unlike the other pool operations, nothing says which pool.
     */
    static AwsException userPoolNotFoundWithoutMessage() {
        return new AwsException("ResourceNotFoundException", "", 400);
    }

    /** A domain prefix already taken, by this pool or another: the same sentence whichever name was asked for. */
    static AwsException domainExists() {
        return new AwsException("InvalidParameterException", "Domain already exists.", 400);
    }
}
