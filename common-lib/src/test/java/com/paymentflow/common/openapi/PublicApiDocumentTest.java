package com.paymentflow.common.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract every service's OpenAPI fragment shares (M21.2).
 *
 * <p><b>Why this is tested here rather than only in the services.</b> Each of the six
 * services asserts its own fragment, but none of them can assert the property that
 * actually matters for M21.3's merge: that all six agree. That property is a consequence
 * of there being one implementation, so it is tested once, where the one implementation
 * lives. A service test proves its fragment is right; this proves there is only one thing
 * for them all to be right about.
 */
class PublicApiDocumentTest {

    @Test
    void everyFragmentCarriesTheSameContractVersionAndTitle() {
        OpenAPI a = PublicApiDocument.forService(PublicApiDocument.tag("A", "first"));
        OpenAPI b = PublicApiDocument.forService(PublicApiDocument.tag("B", "second"));

        // The merged document has exactly one version and one title. Two services
        // disagreeing here is the failure M21.3 would have to arbitrate, and it would have
        // no basis on which to choose.
        assertThat(a.getInfo().getVersion())
                .isEqualTo(b.getInfo().getVersion())
                .isEqualTo(PublicApiDocument.API_VERSION);
        assertThat(a.getInfo().getTitle())
                .isEqualTo(b.getInfo().getTitle())
                .isEqualTo("PaymentFlow API");
        assertThat(a.getInfo().getDescription()).isEqualTo(b.getInfo().getDescription());
    }

    @Test
    void theContractVersionIsADateRatherThanAJarVersion() {
        // §5/M21's versioning scheme is date-based, and M21.5's `PaymentFlow-Version`
        // header will carry exactly this string. "0.0.1-SNAPSHOT" — springdoc's default,
        // taken from the build — would be meaningless to an integrator and unusable as a
        // header value.
        assertThat(PublicApiDocument.API_VERSION).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void theServerIsThePublicEdgeRatherThanAnyServicesOwnPort() {
        OpenAPI document = PublicApiDocument.forService(PublicApiDocument.tag("A", "first"));

        // Six services each publishing their own host would merge into six servers, none
        // of which a merchant calls. There is one public edge.
        assertThat(document.getServers()).hasSize(1);
        assertThat(document.getServers().get(0).getUrl()).isEqualTo("https://api.paymentflow.dev");
    }

    @Test
    void theSecretKeySchemeIsIdenticalAndRequiredDocumentWide() {
        OpenAPI document = PublicApiDocument.forService(PublicApiDocument.tag("A", "first"));

        SecurityScheme scheme = document.getComponents().getSecuritySchemes().get("SecretKey");
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        // Deliberately not "JWT": the value is an opaque sk_ key, and saying otherwise
        // invites an SDK author to decode it.
        assertThat(scheme.getBearerFormat()).isEqualTo("sk");
        assertThat(scheme.getDescription()).contains("sk_test_", "sk_live_");

        // Stated once at the document level, so a service cannot add an endpoint that
        // silently omits it.
        assertThat(document.getSecurity()).hasSize(1);
        assertThat(document.getSecurity().get(0)).containsOnlyKeys("SecretKey");
    }

    @Test
    void tagsAreTheOnePartEachServiceSupplies() {
        List<Tag> tags = List.of(
                PublicApiDocument.tag("Balance", "your balance"),
                PublicApiDocument.tag("Balance transactions", "the entries behind it"));

        OpenAPI document = PublicApiDocument.forService(tags);

        // Order is preserved, because it is a documentation-site navigation decision (M25)
        // rather than whatever order springdoc scanned the controllers in.
        assertThat(document.getTags()).extracting(Tag::getName)
                .containsExactly("Balance", "Balance transactions");
        assertThat(document.getTags()).extracting(Tag::getDescription).doesNotContainNull();
    }
}
