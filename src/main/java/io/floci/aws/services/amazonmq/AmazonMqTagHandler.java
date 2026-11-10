package io.floci.aws.services.amazonmq;

import io.floci.aws.core.common.TagHandler;
import io.floci.aws.core.common.V1Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Amazon MQ's tag endpoints on {@code /v1/tags/{resourceArn}}, for configurations. A separate
 * bean for the reason {@code MskTagHandler} gives. Broker tags are not served here.
 */
@ApplicationScoped
@V1Tags
public class AmazonMqTagHandler implements TagHandler {

    private final AmazonMqConfigurationService configurations;

    @Inject
    public AmazonMqTagHandler(AmazonMqConfigurationService configurations) {
        this.configurations = configurations;
    }

    @Override
    public String serviceKey() {
        return "mq";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return configurations.listTags(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        configurations.tag(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        configurations.untag(arn, tagKeys);
    }
}
