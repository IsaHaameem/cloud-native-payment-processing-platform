package com.paymentflow.payment.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The document-level half of payment-service's OpenAPI description (M21.1) — everything
 * that is true of the API as a whole rather than of one operation: who serves it, what
 * version of it this is, and how a caller authenticates. The per-operation and
 * per-schema detail is generated from the controllers and DTOs themselves.
 *
 * <p><b>Why a bean rather than {@code @OpenAPIDefinition} on the application class.</b>
 * Two of these values are not constants. {@link #API_VERSION} is the date-based public
 * API revision M21 introduces, and the server URL is the public edge rather than this
 * service's own port — both are things that will be read from configuration or derived
 * as M21 proceeds, and an annotation cannot hold either.
 *
 * <p><b>What this document deliberately describes.</b> Only the {@code /v1} tier, which
 * is the public promise. {@code /api/v1} (the dashboard tier, D98) and
 * {@code /internal/v1} (service-to-service) are excluded by
 * {@code springdoc.paths-to-match} in {@code application.yaml}, because §9.5 is explicit
 * that publishing them "would imply a promise the platform does not intend to make".
 * The exclusion is asserted by {@code OpenApiDocumentIntegrationTest} rather than left to
 * configuration nobody re-reads.
 *
 * <p><b>Servers.</b> One entry, naming the public gateway host. A merchant never talks to
 * port 8083; a spec that advertised {@code localhost:8083} would produce SDKs and a
 * console (M22–M24) pointed at a host no integrator can reach. springdoc's default
 * behaviour is exactly that — inferring the server from the incoming request — so the
 * list is set explicitly to override it.
 */
@Configuration
public class OpenApiConfig {

    /**
     * The public API revision, in the date-based scheme §5/M21 adopts and the
     * {@code PaymentFlow-Version} header will carry. It is the version of the *contract*,
     * not of this service's jar (which stays {@code 0.0.1-SNAPSHOT} and means nothing to
     * an integrator).
     *
     * <p>Held here as the single literal for M21.1. The versioning infrastructure that
     * makes it negotiable per merchant and per request is M21's later work; giving it a
     * name now means the transition, when it lands, is a change to one constant's
     * neighbours rather than a search for every place a version was spelled out.
     */
    public static final String API_VERSION = "2026-07-27";

    /** The host merchants actually call. See the class javadoc on why this is not inferred. */
    private static final String PUBLIC_SERVER_URL = "https://api.paymentflow.dev";

    /** The name the security requirement references; must match the key in {@link Components}. */
    private static final String SECRET_KEY_SCHEME = "SecretKey";

    @Bean
    public OpenAPI paymentFlowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PaymentFlow API")
                        .version(API_VERSION)
                        .description("""
                                The PaymentFlow payments API. This document describes the public \
                                `/v1` surface — the endpoints PaymentFlow versions, deprecates on a \
                                published timeline, and supports for integrators. Endpoints not \
                                described here are internal and carry no compatibility promise.

                                Amounts are integers in the currency's minor unit (`amountMinor`): \
                                `1000` in `USD` is $10.00. There are no floating-point amounts \
                                anywhere in this API.

                                Every mutating request accepts an `Idempotency-Key` header and every \
                                list is cursor-paginated; both are described on the operations that \
                                use them.""")
                        .license(new License().name("Proprietary")))
                // Not inferred from the request — see the class javadoc.
                .servers(List.of(new Server()
                        .url(PUBLIC_SERVER_URL)
                        .description("PaymentFlow API")))
                // Declared on the document, not on the controller, for two reasons: the
                // list is what fixes the *order* resources appear in (a docs-site
                // navigation decision, not a Java one), and a tag's description belongs
                // wherever the merged multi-service spec will need to state it once.
                .tags(List.of(
                        new Tag().name("Payments")
                                .description("Create, authorize, capture, void, and read payments."),
                        new Tag().name("Refunds")
                                .description("Read the refunds issued against your payments.")))
                .components(new Components().addSecuritySchemes(SECRET_KEY_SCHEME, secretKeyScheme()))
                // Applied at the document level rather than per operation: every /v1
                // endpoint requires a key without exception, so stating it once is both
                // shorter and impossible to forget on a new endpoint.
                .addSecurityItem(new SecurityRequirement().addList(SECRET_KEY_SCHEME));
    }

    /**
     * HTTP bearer authentication carrying a PaymentFlow secret key — the form
     * {@code docs/READ_APIS.md} §2 publishes, and the only credential the public tier
     * accepts (M15/D100).
     *
     * <p>Described as {@code bearerFormat: sk} rather than the usual {@code JWT} because
     * the value genuinely is not one: it is an opaque {@code sk_test_}/{@code sk_live_}
     * key, and the prefix is what selects the mode. Saying {@code JWT} here would invite
     * an SDK author to try to decode it.
     */
    private static SecurityScheme secretKeyScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("sk")
                .description("""
                        Your secret API key, sent as `Authorization: Bearer sk_test_...`.

                        The key determines both *whose* data you see and *which mode* you see it \
                        in: an `sk_test_` key reads and writes test data only, an `sk_live_` key \
                        live data only. No header, parameter, or body field can change either — \
                        to work in the other mode, use the other key.""");
    }
}
