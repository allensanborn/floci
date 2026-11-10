package io.floci.aws.services.amazonmq;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Moves Amazon MQ's {@code /v1/configurations} requests onto an internal prefix before route
 * matching. MSK serves the same path and the two differ only by SigV4 signing service, so a
 * request signed for {@code mq} is rewritten and one signed for {@code kafka} is left for MSK.
 */
@Provider
@PreMatching
public class AmazonMqRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_mq";

    private final SignedServiceRoute route;

    @Inject
    public AmazonMqRouteFilter(ResolvedServiceCatalog catalog) {
        this.route = new SignedServiceRoute(catalog, "mq", INTERNAL_PREFIX,
                SignedServiceRoute.under("/v1/configurations"));
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
