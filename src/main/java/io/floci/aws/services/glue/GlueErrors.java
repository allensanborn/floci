package io.floci.aws.services.glue;

import io.floci.aws.core.common.AwsException;

import java.util.List;

/**
 * Glue's not-found, already-exists and invalid-input errors, worded as AWS words them. AWS's
 * wording is per operation, per resource and sometimes per state, not one template: some misses
 * name the resource, some are a fixed sentence, and a few carry a doubled or empty message.
 *
 * <p>Most messages are asserted by AWS-verified behavioural checks (the evaluation suite's glue
 * checks). The rest, among them the DeleteClassifier, DeleteSecurityConfiguration and the
 * partition operations' table miss, are the wording the Rust emulator's recorded AWS responses
 * carry and no check here pins. Change one only with a recorded AWS response.
 */
final class GlueErrors {

    private static final String NOT_FOUND = "EntityNotFoundException";
    private static final String ALREADY_EXISTS = "AlreadyExistsException";
    private static final String INVALID_INPUT = "InvalidInputException";

    private GlueErrors() {
    }

    private static AwsException notFound(String message) {
        return new AwsException(NOT_FOUND, message, 400);
    }

    private static AwsException exists(String message) {
        return new AwsException(ALREADY_EXISTS, message, 400);
    }

    static AwsException invalidInput(String message) {
        return new AwsException(INVALID_INPUT, message, 400);
    }

    static AwsException databaseNotFound(String name) {
        return notFound("Database " + name + " not found.");
    }

    static AwsException databaseAlreadyExists() {
        return exists("Database already exists.");
    }

    /** GetTable's miss (and the default for table lookups): names neither table nor database. */
    static AwsException tableEntityNotFound() {
        return notFound("Entity Not Found");
    }

    /** UpdateTable, DeleteTable and GetPartitionIndexes name the table, without its database. */
    static AwsException tableNotFound(String tableName) {
        return notFound("Table " + tableName + " not found.");
    }

    static AwsException tableAlreadyExists() {
        return exists("Table already exists.");
    }

    /**
     * The wording follows the version's state, not the operation: an id at or below the table's
     * current version counter was minted once and has since been deleted, so the miss reads "Table
     * version not found."; an id above it never existed, so it reads "Version not found.".
     */
    static AwsException tableVersionMiss(String currentVersionId, String requestedVersionId) {
        boolean minted = Long.parseLong(requestedVersionId) <= Long.parseLong(currentVersionId);
        return notFound(minted ? "Table version not found." : "Version not found.");
    }

    static AwsException cannotDeleteLatestVersion() {
        return invalidInput("VersionId cannot be equal to the latest.");
    }

    static AwsException partitionNotFound() {
        return notFound("Cannot find partition.");
    }

    static String partitionNotFoundMessage(List<String> values) {
        return "Partition [" + String.join(", ", values) + "] not found";
    }

    /** UpdatePartition's miss. */
    static AwsException partitionNotFound(List<String> values) {
        return notFound(partitionNotFoundMessage(values));
    }

    static AwsException jobNotFound() {
        return notFound("Job not found.");
    }

    static AwsException jobRunNotFound() {
        return notFound("Job run not found for the given job name and runId.");
    }

    static AwsException jobStartMetadataMissing() {
        return notFound("Failed to start job run due to missing metadata.");
    }

    /** The service composes its per-resource sentence with its generic one, hence the doubling. */
    static AwsException classifierNotFound(String name) {
        return notFound("Classifier " + name + " not found. not found.");
    }

    static AwsException classifierNotFoundForDelete(String name, String accountId) {
        return notFound("Classifier " + accountId + ":" + name + " not found.");
    }

    static AwsException crawlerNotFound(String name) {
        return notFound("Crawler entry with name " + name + " does not exist");
    }

    static AwsException crawlerTargetsNotDefined() {
        return invalidInput("Targets are not defined");
    }

    /**
     * BatchDeleteConnection's per-entry error: the code is InternalException, which the check rejects
     * the entity-miss code in favour of, and the message is GetConnection's sentence.
     */
    static GlueService.ErrorDetail batchConnectionMiss(String name, String accountId) {
        return new GlueService.ErrorDetail("InternalException", connectionNotFound(name, accountId).getMessage());
    }

    /** GetConnection. */
    static AwsException connectionNotFound(String name, String accountId) {
        return notFound("Connection with name " + name + " in account " + accountId + " is not found");
    }

    /** UpdateConnection, and the per-entry error BatchDeleteConnection reports. */
    static AwsException connectionNotFoundNamed(String name) {
        return notFound("Connection not found: " + name);
    }

    static AwsException connectionNotFoundForDelete() {
        return notFound("Connection not found");
    }

    static AwsException triggerNotFound() {
        return notFound("Trigger not found.");
    }

    static AwsException triggerPredicateRequired() {
        return invalidInput("Predicate cannot be null or empty");
    }

    static String invalidScheduleMessage(String expression) {
        return "The schedule " + expression + " is invalid.";
    }

    static AwsException dataQualityRulesetNotFound(String name, String accountId) {
        return notFound("Cannot find Data Quality Ruleset in account " + accountId + " with name " + name);
    }

    /** GetSecurityConfiguration's miss carries an empty message. */
    static AwsException securityConfigurationNotFound() {
        return notFound("");
    }

    /** A malformed ResourceArn on the tagging calls is refused with an empty message. */
    static AwsException invalidArn() {
        return invalidInput("");
    }
}
