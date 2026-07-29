package com.paymentflow.common.openapi;

import com.paymentflow.common.dto.version.ApiVersions;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;

import java.util.List;

/**
 * The document-level half of every service's OpenAPI description (M21.2) — the parts that
 * are true of the <em>API</em> rather than of any one service that happens to implement a
 * piece of it: its title, its date-based contract version, the host merchants call, and
 * how a caller authenticates.
 *
 * <p><b>Why this is shared rather than repeated per service.</b> Six services expose the
 * public {@code /v1} tier, and M21.3 merges their six fragments into the single
 * {@code openapi.yaml} the platform publishes. A merge is only meaningful if the fragments
 * agree: two services declaring different {@code info.version} values, or the same
 * {@code SecretKey} scheme with differently-worded descriptions, produce a merged document
 * that either conflicts or silently takes whichever fragment was read last. Hand-copying
 * this block into six {@code OpenApiConfig} classes makes that drift a matter of time
 * rather than possibility, and no test inside any single service could detect it.
 *
 * <p>This is the same reasoning D140 applied to {@code CanonicalEventType}: the general
 * rule in this codebase is schema-per-service (D4/D36), because a producer's internal
 * payload shape is its own business — but a <em>frozen public contract</em> that several
 * services must render identically is the documented exception, and it belongs in
 * {@code common-dto}/{@code common-lib} where exactly one copy exists.
 *
 * <p><b>What is deliberately left to each service:</b> its tags. Those genuinely differ —
 * they name the resources that service owns — and a service that forgot to declare one
 * would produce an operation filed under a tag the document never describes, which its own
 * {@code OpenApiDocumentIntegrationTest} catches.
 *
 * <p><b>Why a static factory rather than an auto-configuration.</b> An auto-configured
 * {@code OpenAPI} bean would have to discover each service's tags through yet another
 * bean type, turning one explicit call into two indirections. The tags have to be written
 * down somewhere regardless; a service's {@code OpenApiConfig} naming its own resources
 * and delegating everything else reads as what it is.
 */
public final class PublicApiDocument {

    /**
     * The public API revision, in the date-based scheme §5/M21 adopts and the
     * {@code PaymentFlow-Version} header will carry. It is the version of the
     * <em>contract</em>, not of any service's jar (each stays {@code 0.0.1-SNAPSHOT},
     * which means nothing to an integrator).
     *
     * <p>One literal, shared by every service, because a merged document has exactly one
     * version. The infrastructure that makes it negotiable per merchant and per request is
     * M21.5's; naming it here means that transition edits this constant's neighbours
     * rather than hunting six copies.
     */
    public static final String API_VERSION = ApiVersions.CURRENT.toString();

    /** The title of the merged document. Identical across fragments by construction. */
    public static final String TITLE = "PaymentFlow API";

    /** The host merchants actually call — the gateway, never a service's own port. */
    public static final String PUBLIC_SERVER_URL = "https://api.paymentflow.dev";

    /** The name the security requirement references; must match the key in {@link Components}. */
    public static final String SECRET_KEY_SCHEME = "SecretKey";

    private static final String DESCRIPTION = """
            The PaymentFlow payments API. This document describes the public `/v1` surface \
            — the endpoints PaymentFlow versions, deprecates on a published timeline, and \
            supports for integrators. Endpoints not described here are internal and carry \
            no compatibility promise.

            Amounts are integers in the currency's minor unit (`amountMinor`): `1000` in \
            `USD` is $10.00. There are no floating-point amounts anywhere in this API.

            Every mutating request accepts an `Idempotency-Key` header and every list is \
            cursor-paginated; both are described on the operations that use them.""";

    private static final String SECRET_KEY_DESCRIPTION = """
            Your secret API key, sent as `Authorization: Bearer sk_test_...`.

            The key determines both *whose* data you see and *which mode* you see it in: \
            an `sk_test_` key reads and writes test data only, an `sk_live_` key live data \
            only. No header, parameter, or body field can change either — to work in the \
            other mode, use the other key.""";

    private PublicApiDocument() {
    }

    /**
     * Builds this service's fragment of the public API document: the shared contract
     * above, plus the resource tags this service owns.
     *
     * @param tags the resources this service documents, in the order they should appear.
     *             The order is a documentation-site navigation decision (M25), which is
     *             why it is stated rather than derived from whatever order springdoc
     *             happened to scan the controllers in.
     */
    public static OpenAPI forService(List<Tag> tags) {
        return new OpenAPI()
                .info(new Info()
                        .title(TITLE)
                        .version(API_VERSION)
                        .description(DESCRIPTION)
                        .license(new License().name("Proprietary")))
                // Set explicitly to override springdoc's default, which infers the server
                // from the incoming request — that would publish `localhost:8084` (or a
                // test's random port) as the host an SDK should call.
                .servers(List.of(new Server()
                        .url(PUBLIC_SERVER_URL)
                        .description(TITLE)))
                .tags(List.copyOf(tags))
                .components(new Components().addSecuritySchemes(SECRET_KEY_SCHEME, secretKeyScheme()))
                // Applied at the document level rather than per operation, so a new
                // endpoint cannot be added without it. The one endpoint in the platform
                // that is genuinely unauthenticated (`GET /v1/test/cards`, §8.1) clears
                // this with an empty `@SecurityRequirements` at the operation — an
                // explicit exception, which is the right shape for it.
                .addSecurityItem(new SecurityRequirement().addList(SECRET_KEY_SCHEME));
    }

    /** Convenience for the common case of naming tags inline. */
    public static OpenAPI forService(Tag... tags) {
        return forService(List.of(tags));
    }

    /**
     * A resource tag: the name a group of operations appears under on the documentation
     * site (M25) and the name its methods are grouped by in the generated SDKs (M22).
     */
    public static Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }

    /**
     * The standard error responses, applied to every operation in the service that exposes
     * this bean (M21.4). Paired with {@link #errorSchemaCustomizer()}, which puts the
     * {@code ApiError} schema they reference into the document.
     *
     * <p>Exposed here rather than built in each service's config for the same reason the
     * {@code info} block lives here: 401, 403, 429 and 500 are properties of the tier, not
     * of any service, and six copies of the same customizer is six chances to differ.
     */
    public static OperationCustomizer errorResponseCustomizer() {
        return (operation, handlerMethod) -> PublicApiErrorResponses.apply(operation);
    }

    /**
     * Registers the {@code ApiError} schema the standard error responses reference, and
     * describes the schemas every fragment shares (M21.7).
     *
     * <p>Both jobs in one customizer because they are the same job: making the components
     * that appear in all six fragments identical everywhere. M21.3's merge <em>refuses</em>
     * to combine a component two services define differently, so anything applied to
     * {@code ApiError} or a pagination envelope has to be applied from one place — six
     * annotated copies would have to stay byte-identical forever.
     */
    public static OpenApiCustomizer sharedSchemaCustomizer() {
        return document -> {
            PublicApiErrorResponses.registerSchemas(document);
            PublicApiSchemas.describeSharedSchemas(document);
        };
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
                .description(SECRET_KEY_DESCRIPTION);
    }
}
