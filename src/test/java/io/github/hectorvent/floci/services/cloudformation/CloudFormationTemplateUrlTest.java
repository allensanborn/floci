package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationService.S3TemplateRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bucket/key extraction from a {@code TemplateURL}, which decides whether the URL is
 * virtual-hosted or path-style.
 */
class CloudFormationTemplateUrlTest {

    private static final String SUFFIX = EmbeddedDnsServer.DEFAULT_SUFFIX;

    @Test
    void pathStyleAgainstTheLocalS3ServiceHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.localhost.floci.io:4566/bucket/key", SUFFIX);

        assertEquals("bucket", ref.bucket());
        assertEquals("key", ref.key());
    }

    @Test
    void pathStyleAgainstTheRegionalLocalS3ServiceHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.us-east-1.localhost.floci.io:4566/cdk-hnb659fds-assets-000000000000-us-east-1/d05ac.json",
                SUFFIX);

        assertEquals("cdk-hnb659fds-assets-000000000000-us-east-1", ref.bucket());
        assertEquals("d05ac.json", ref.key());
    }

    @Test
    void pathStyleAgainstTheBareLocalhostS3ServiceHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.localhost:4566/bucket/nested/key.json", SUFFIX);

        assertEquals("bucket", ref.bucket());
        assertEquals("nested/key.json", ref.key());
    }

    @Test
    void virtualHostedAgainstTheConfiguredSuffix_readsBucketFromTheHost() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://bucket.localhost.floci.io:4566/key", SUFFIX);

        assertEquals("bucket", ref.bucket());
        assertEquals("key", ref.key());
    }

    @Test
    void virtualHostedAgainstTheS3QualifiedLocalHost_readsBucketFromTheHost() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://bucket.s3.localhost.floci.io:4566/nested/key.json", SUFFIX);

        assertEquals("bucket", ref.bucket());
        assertEquals("nested/key.json", ref.key());
    }

    @Test
    void virtualHostedAgainstBareLocalhost_readsBucketFromTheHost() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://bucket.localhost:4566/key", SUFFIX);

        assertEquals("bucket", ref.bucket());
        assertEquals("key", ref.key());
    }

    @Test
    void pathStyleAgainstAws_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "https://s3.us-east-1.amazonaws.com/bucket/key", SUFFIX);

        assertEquals("bucket", ref.bucket());
        assertEquals("key", ref.key());
    }

    @Test
    void virtualHostedAgainstAws_readsBucketFromTheHost() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "https://bucket.s3.us-east-1.amazonaws.com/nested/key.json", SUFFIX);

        assertEquals("bucket", ref.bucket());
        assertEquals("nested/key.json", ref.key());
    }

    @Test
    void pathStyleAgainstAnUnknownHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://127.0.0.1:4566/bucket/key", SUFFIX);

        assertEquals("bucket", ref.bucket());
        assertEquals("key", ref.key());
    }

    @Test
    void pathStyleWithoutAKey_readsTheBucketAndAnEmptyKey() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.localhost.floci.io:4566/bucket", SUFFIX);

        assertEquals("bucket", ref.bucket());
        assertEquals("", ref.key());
    }

    @Test
    void virtualHostedForABucketNamedS3_onTheConfiguredSuffix_keepsTheBucket() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.s3.localhost.floci.io:4566/key.json", SUFFIX);

        assertEquals("s3", ref.bucket());
        assertEquals("key.json", ref.key());
    }

    @Test
    void virtualHostedForABucketNamedS3_onAws_keepsTheBucket() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "https://s3.s3.us-east-1.amazonaws.com/nested/key.json", SUFFIX);

        assertEquals("s3", ref.bucket());
        assertEquals("nested/key.json", ref.key());
    }

    @Test
    void virtualHostedForABucketNamedS3_onBareLocalhost_keepsTheBucket() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.s3.localhost:4566/key.json", SUFFIX);

        assertEquals("s3", ref.bucket());
        assertEquals("key.json", ref.key());
    }

    @Test
    void theRegionalServiceHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.us-east-1.localhost.floci.io:4566/bucket/key.json", SUFFIX);

        assertEquals("bucket", ref.bucket());
        assertEquals("key.json", ref.key());
    }
}
