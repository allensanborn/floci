package io.floci.aws.services.cognito;

import io.floci.aws.core.common.AwsException;

/**
 * The error messages Cognito User Pools gives for refusals Floci used to word its own way. Each
 * constant or factory is the exact text AWS answers, so a caller that matches on it, or an SDK that
 * surfaces it, sees what a real pool sends; the codes and statuses are unchanged by living here.
 */
final class CognitoErrors {

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
}
