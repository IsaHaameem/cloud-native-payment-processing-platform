package com.paymentflow.gateway.version;

import com.paymentflow.common.dto.version.ApiVersion;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Rewrites outbound response bodies back into the revision the caller is pinned to
 * (M21.5, §4.10).
 *
 * <p>This is the half of the versioning layer that carries the actual promise: a caller
 * pinned to a superseded revision must not be able to tell that the platform moved on.
 * Everything downstream produces the current shape; this walks it back.
 *
 * <p><b>Why a response decorator and not a Gateway filter factory.</b> Spring Cloud
 * Gateway's {@code ModifyResponseBody} filter factory does this per route, and there are
 * many routes — wiring it onto each would put a versioning concern into every route
 * definition and guarantee that the next route added forgets it. A single decorating
 * {@link WebFilter} covers everything the gateway proxies, including routes that do not
 * exist yet.
 *
 * <p><b>Buffering is unavoidable and is bounded by what it applies to.</b> Rewriting JSON
 * requires the whole document, so this joins the response into one buffer before parsing.
 * That is only done when a transformation actually applies — a caller on the current
 * revision, which is almost all of them, is never buffered and never parsed. The bodies that
 * are buffered are single objects and cursor pages, both bounded by the API's own page
 * limits.
 *
 * <p><b>Failure is deliberately transparent.</b> If the body is not JSON, is empty, or fails
 * to parse, it is passed through untouched. A versioning layer that could turn a working
 * response into an error would be a worse bargain than the compatibility it buys, and a
 * streamed non-JSON response (none today, but nothing prevents one) must not be corrupted by
 * a filter that assumed otherwise.
 */
@Component
public class ApiVersionResponseBodyFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ApiVersionResponseBodyFilter.class);

    private final ApiTransformationRegistry registry;
    private final ObjectMapper objectMapper;

    public ApiVersionResponseBodyFilter(ApiTransformationRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        // Immediately after ApiVersionWebFilter (+40), which resolves the version this reads.
        return Ordered.HIGHEST_PRECEDENCE + 41;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Object resolved = exchange.getAttributes().get(ApiVersionWebFilter.RESOLVED_VERSION_ATTRIBUTE);
        if (!(resolved instanceof ApiVersion version)) {
            return chain.filter(exchange);
        }
        List<ApiTransformation> transformations = registry.forResponse(version);
        if (transformations.isEmpty()) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange.mutate()
                .response(decorate(exchange, transformations))
                .build());
    }

    private ServerHttpResponse decorate(ServerWebExchange exchange, List<ApiTransformation> transformations) {
        String path = exchange.getRequest().getPath().value();
        ServerHttpResponse original = exchange.getResponse();

        return new ServerHttpResponseDecorator(original) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                if (!isJson(original)) {
                    return super.writeWith(body);
                }
                return super.writeWith(DataBufferUtils.join(Flux.from(body)).map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);

                    byte[] rewritten = transform(bytes, path, transformations);
                    // Content-Length changes whenever the transformation does: `authorized`
                    // is nine bytes shorter than `AUTHORIZED`. Leaving a stale length is the
                    // classic way a body-rewriting filter produces a truncated response that
                    // looks like a network fault.
                    original.getHeaders().setContentLength(rewritten.length);
                    return original.bufferFactory().wrap(rewritten);
                }));
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return writeWith(Flux.from(body).flatMapSequential(publisher -> publisher));
            }
        };
    }

    private byte[] transform(byte[] bytes, String path, List<ApiTransformation> transformations) {
        if (bytes.length == 0) {
            return bytes;
        }
        try {
            JsonNode body = objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            for (ApiTransformation transformation : transformations) {
                body = transformation.transformResponseBody(path, body);
            }
            return objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            // Pass through rather than fail — see the class javadoc.
            log.warn("Could not apply a version transformation to the response for {}; "
                    + "sending it unchanged", path, e);
            return bytes;
        }
    }

    private static boolean isJson(ServerHttpResponse response) {
        MediaType contentType = response.getHeaders().getContentType();
        return contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType);
    }
}
