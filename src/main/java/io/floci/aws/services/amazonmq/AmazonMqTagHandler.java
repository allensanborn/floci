package io.floci.aws.services.amazonmq;

import io.floci.aws.core.common.TagHandler;
import io.floci.aws.core.common.V1Tags;
import io.floci.aws.services.amazonmq.model.MqConfiguration;
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
        return configurations.byArn(arn).tags;
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        MqConfiguration c = configurations.byArn(arn);
        c.tags.putAll(tags);
        configurations.save(c);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        MqConfiguration c = configurations.byArn(arn);
        tagKeys.forEach(c.tags::remove);
        configurations.save(c);
    }
}
