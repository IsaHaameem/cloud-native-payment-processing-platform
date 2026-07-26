package com.paymentflow.gateway.logging;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.redaction.RequestRedactor;
import com.paymentflow.gateway.config.RequestLoggingProperties;
import com.paymentflow.gateway.security.apikey.ApiKeyAuthenticationWebFilter;
import com.paymentflow.gateway.security.apikey.ApiKeyVerifyResult;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures every attributable API request as an {@code api.request.events} message
 * (M20.2, §5/M20 task 1).
 *
 * <p><b>Ordering.</b> Runs immediately after {@link com.paymentflow.gateway.filter.CorrelationIdWebFilter}
 * and ahead of authentication, because it has to wrap the entire exchange to measure real
 * latency and observe the final status — including responses written by the security layer,
 * which never reach a later filter. It therefore cannot read the internal-context headers
 * authentication produces; it reads the resolved key context from an exchange attribute
 * instead ({@link ApiKeyAuthenticationWebFilter#RESOLVED_KEY_CONTEXT_ATTRIBUTE}), which flows
 * back up because {@code mutate()} shares the attribute map.
 *
 * <p><b>Only attributable requests are logged, and that is a deliberate scoping decision.</b>
 * The request log is a <em>merchant-facing</em> object: it is read through
 * {@code GET /v1/request_logs} scoped to the caller's merchant and mode. A request whose API
 * key never resolved has no merchant, so it cannot be filed in anyone's log without either
 * inventing an owner or creating a bucket every merchant can read — the second of which
 * would be a cross-tenant leak in a feature built for debugging. Unauthenticated and
 * JWT/dashboard traffic is out of scope for the same reason; operator-facing visibility for
 * those already exists in M13's Prometheus and Tempo. A request that resolved a key and was
 * <em>then</em> refused (403 insufficient scope, 429 rate limited) <b>is</b> logged — that is
 * exactly the outcome a developer needs explained.
 *
 * <p><b>Nothing here may fail a request.</b> Capture is wrapped so that a failure in logging
 * cannot propagate into the chain, and the publish call is non-blocking by construction
 * (D109).
 */
@Component
public class ApiRequestLoggingFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ApiRequestLoggingFilter.class);

    /**
     * Bodies are only captured for content types that are actually inspectable text. A
     * multipart upload or an octet-stream is binary: storing 4 KB of its middle would be
     * unreadable in a request log and would defeat redaction, which reasons about text.
     */
    private static final List<String> CAPTURABLE_CONTENT_TYPES =
            List.of("application/json", "application/x-www-form-urlencoded", "text/");

    private final ApiRequestEventPublisher publisher;
    private final RequestLoggingProperties properties;

    public ApiRequestLoggingFilter(ApiRequestEventPublisher publisher, RequestLoggingProperties properties) {
        this.publisher = publisher;
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        // Just after CorrelationIdWebFilter (HIGHEST_PRECEDENCE), so correlation and request
        // ids already exist, and well before Spring Security's filter at -100.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!properties.enabled()) {
            return chain.filter(exchange);
        }

        long startNanos = System.nanoTime();
        BodyCapture requestCapture = new BodyCapture(properties.maxBodyBytes());
        BodyCapture responseCapture = new BodyCapture(properties.maxBodyBytes());

        ServerWebExchange decorated = shouldCaptureBodies()
                ? exchange.mutate()
                    .request(new CapturingRequestDecorator(exchange.getRequest(), requestCapture))
                    .response(new CapturingResponseDecorator(exchange.getResponse(), responseCapture))
                    .build()
                : exchange;

        return chain.filter(decorated)
                .doFinally(signal -> safelyEmit(decorated, startNanos, requestCapture, responseCapture));
    }

    private boolean shouldCaptureBodies() {
        return properties.captureBodies() && properties.maxBodyBytes() > 0;
    }

    /**
     * Builds and hands off the event. Every failure is swallowed with a log line: this runs
     * in {@code doFinally}, where a thrown exception would surface as a dropped or corrupted
     * response on a request that had already succeeded.
     */
    private void safelyEmit(ServerWebExchange exchange, long startNanos,
                            BodyCapture requestCapture, BodyCapture responseCapture) {
        try {
            Object attribute = exchange.getAttributes().get(ApiKeyAuthenticationWebFilter.RESOLVED_KEY_CONTEXT_ATTRIBUTE);
            if (!(attribute instanceof ApiKeyVerifyResult context)) {
                return;
            }
            publisher.publish(buildPayload(exchange, context, startNanos, requestCapture, responseCapture));
        } catch (Exception e) {
            log.warn("Failed to record a request-log event — the request itself was unaffected", e);
        }
    }

    private ApiRequestEventPayload buildPayload(ServerWebExchange exchange, ApiKeyVerifyResult context,
                                                long startNanos, BodyCapture requestCapture,
                                                BodyCapture responseCapture) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        int status = response.getStatusCode() == null ? 0 : response.getStatusCode().value();

        return new ApiRequestEventPayload(
                context.merchantId(),
                context.keyId(),
                context.mode(),
                request.getMethod().name(),
                request.getPath().value(),
                // The query string is redacted like a body: a key pasted into `?api_key=` is
                // the same leak by another route, and M20.1's name pass handles it.
                RequestRedactor.redactText(request.getURI().getRawQuery()),
                status,
                durationMs,
                clientIp(request),
                request.getHeaders().getFirst(HttpHeaders.USER_AGENT),
                request.getHeaders().getFirst(CorrelationConstants.CORRELATION_ID_HEADER),
                request.getHeaders().getFirst(CorrelationConstants.REQUEST_ID_HEADER),
                errorCodeFor(status),
                redactCaptured(requestCapture, request.getHeaders().getContentType() == null
                        ? null : request.getHeaders().getContentType().toString()),
                redactCaptured(responseCapture, response.getHeaders().getContentType() == null
                        ? null : response.getHeaders().getContentType().toString()),
                RequestRedactor.redactHeaders(toMultiMap(request.getHeaders())));
    }

    /**
     * Spring 7's {@code HttpHeaders} is no longer a {@code Map<String, List<String>>}, and
     * its {@code asMultiValueMap()} bridge is already deprecated for removal — so the view
     * the shared redactor takes is built explicitly rather than borrowed from an API on its
     * way out.
     */
    private static Map<String, List<String>> toMultiMap(HttpHeaders headers) {
        Map<String, List<String>> multiMap = new LinkedHashMap<>();
        for (String name : headers.headerNames()) {
            List<String> values = headers.get(name);
            multiMap.put(name, values == null ? List.of() : values);
        }
        return multiMap;
    }

    private String redactCaptured(BodyCapture capture, String contentType) {
        if (!shouldCaptureBodies() || !isCapturable(contentType)) {
            return null;
        }
        return RequestRedactor.redactBody(capture.asString(), properties.maxBodyBytes());
    }

    private static boolean isCapturable(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        return CAPTURABLE_CONTENT_TYPES.stream().anyMatch(lower::startsWith);
    }

    /**
     * A coarse classification, not the downstream service's own error code — the gateway
     * cannot see that without parsing every error body, which would cost more than it tells.
     * M21 owns the real error contract; this field exists so a developer can filter a request
     * log by "what went wrong" today.
     */
    private static String errorCodeFor(int status) {
        if (status < 400) {
            return null;
        }
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "INSUFFICIENT_SCOPE";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 422 -> "UNPROCESSABLE";
            case 429 -> "RATE_LIMITED";
            default -> status >= 500 ? "SERVER_ERROR" : "CLIENT_ERROR";
        };
    }

    private static String clientIp(ServerHttpRequest request) {
        // X-Forwarded-For is already normalised by the trusted-proxies configuration in
        // application.yaml, so getRemoteAddress reflects the real client behind the ALB.
        InetSocketAddress remote = request.getRemoteAddress();
        return (remote != null && remote.getAddress() != null) ? remote.getAddress().getHostAddress() : null;
    }

    /**
     * A bounded, self-limiting byte sink. Once {@code maxBytes} have been seen it stops
     * copying entirely — the body keeps flowing to its real destination untouched, but the
     * gateway's memory cost per request is capped no matter how large the payload is.
     */
    private static final class BodyCapture {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int maxBytes;

        private BodyCapture(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        private synchronized void append(byte[] bytes) {
            int remaining = maxBytes - buffer.size();
            if (remaining <= 0) {
                return;
            }
            buffer.write(bytes, 0, Math.min(remaining, bytes.length));
        }

        private synchronized String asString() {
            return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /** Tees the request body into a capture buffer without consuming or altering it. */
    private static final class CapturingRequestDecorator extends ServerHttpRequestDecorator {
        private final BodyCapture capture;

        private CapturingRequestDecorator(ServerHttpRequest delegate, BodyCapture capture) {
            super(delegate);
            this.capture = capture;
        }

        @Override
        public Flux<DataBuffer> getBody() {
            return super.getBody().doOnNext(dataBuffer -> copyInto(capture, dataBuffer));
        }
    }

    /** The same tee on the way out. */
    private static final class CapturingResponseDecorator extends ServerHttpResponseDecorator {
        private final BodyCapture capture;

        private CapturingResponseDecorator(ServerHttpResponse delegate, BodyCapture capture) {
            super(delegate);
            this.capture = capture;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return super.writeWith(Flux.from(body).doOnNext(dataBuffer -> copyInto(capture, dataBuffer)));
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).flatMapSequential(inner -> inner));
        }
    }

    /**
     * Reads a buffer's bytes <em>without</em> moving its read position — the single most
     * important detail in this file. Consuming the buffer here would deliver an empty body to
     * the real destination, turning an observability feature into data loss.
     */
    private static void copyInto(BodyCapture capture, DataBuffer dataBuffer) {
        try {
            int readable = dataBuffer.readableByteCount();
            if (readable <= 0) {
                return;
            }
            byte[] bytes = new byte[readable];
            dataBuffer.toByteBuffer(0, java.nio.ByteBuffer.wrap(bytes), 0, readable);
            capture.append(bytes);
        } catch (Exception e) {
            log.debug("Could not capture a body fragment for the request log — continuing", e);
        }
    }

}
