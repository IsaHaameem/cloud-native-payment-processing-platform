package com.paymentflow.notification.egress;

import com.paymentflow.notification.TestWebhookProperties;
import com.paymentflow.notification.config.WebhookProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hostile-URL table (M18.5, §5/M18's testing strategy, R5). Webhook delivery is the
 * only place this platform makes an outbound request to a merchant-chosen destination
 * from inside the VPC, so this is the milestone's highest-consequence test — a gap here
 * is a request-forgery primitive, not a bug.
 *
 * <p>DNS is injected rather than real, which is what makes the table exhaustive: a name
 * that resolves to a private address, or to one public *and* one private address, can be
 * expressed here without owning a domain or depending on the internet from a unit test.
 */
class EgressPolicyTest {

    private static WebhookProperties properties(boolean requireHttps, List<String> allowedHosts) {
        return TestWebhookProperties.builder()
                .requireHttps(requireHttps)
                .allowedHosts(allowedHosts)
                .build();
    }

    /** Resolves any host to the literal address embedded in its name, so the table controls DNS exactly. */
    private static EgressPolicy.HostResolver resolverReturning(String... addresses) {
        return host -> {
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                resolved[i] = InetAddress.getByName(addresses[i]);
            }
            return resolved;
        };
    }

    private static EgressPolicy policyResolvingTo(String... addresses) {
        return new EgressPolicy(properties(false, List.of()), resolverReturning(addresses));
    }

    @ParameterizedTest(name = "blocks {0} ({1})")
    @CsvSource({
            // Loopback — reaches this service's own actuator and every co-located process.
            "http://localhost/hook,                       127.0.0.1",
            "http://127.0.0.1/hook,                       127.0.0.1",
            "http://127.0.0.53/hook,                      127.0.0.53",
            "http://[::1]/hook,                           ::1",
            // Link-local, including the cloud instance-metadata address — the single
            // highest-value SSRF target in any cloud deployment.
            "http://169.254.169.254/latest/meta-data,     169.254.169.254",
            "http://metadata.internal/latest/meta-data,   169.254.169.254",
            "http://[fe80::1]/hook,                       fe80::1",
            // Private ranges — every other service in the VPC.
            "http://10.0.0.5/hook,                        10.0.0.5",
            "http://172.16.4.1/hook,                      172.16.4.1",
            "http://172.31.255.254/hook,                  172.31.255.254",
            "http://192.168.1.10/hook,                    192.168.1.10",
            "http://[fd00::1]/hook,                       fd00::1",
            // Wildcard / any — routes to localhost on several stacks.
            "http://0.0.0.0/hook,                         0.0.0.0",
            // Carrier-grade NAT — neither 'site local' nor 'any local' to java.net, and
            // not a destination on the public internet either.
            "http://100.64.0.1/hook,                      100.64.0.1",
            // Multicast.
            "http://224.0.0.1/hook,                       224.0.0.1",
            // The classic bypass: a public-looking hostname whose DNS answer is private.
            "http://totally-legit-webhooks.test/hook,     10.1.2.3",
    })
    void everyHostileDestinationIsRefused(String url, String resolvesTo) {
        EgressDecision decision = policyResolvingTo(resolvesTo).check(url.trim());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isNotBlank();
        assertThat(decision.resolvedAddresses()).isEmpty();
    }

    @ParameterizedTest(name = "blocks IPv6-wrapped {0}")
    @ValueSource(strings = {
            // An IPv4-mapped IPv6 address is the same forbidden address wearing a costume.
            // java.net's own isLoopbackAddress()/isSiteLocalAddress() do not see through
            // either form, which is exactly why this is the check most often missed.
            "::ffff:127.0.0.1",
            "::ffff:169.254.169.254",
            "::ffff:10.0.0.1",
            "::ffff:192.168.0.1",
    })
    void anIpv4AddressWrappedInIpv6IsStillBlocked(String address) {
        assertThat(policyResolvingTo(address).check("http://disguised.test/hook").allowed()).isFalse();
    }

    @Test
    void aHostResolvingToBothAPublicAndAPrivateAddressIsRefused() {
        // Checking only the first answer would let this through, and which answer comes
        // first is the attacker's choice, not ours.
        EgressPolicy policy = policyResolvingTo("93.184.216.34", "10.0.0.7");

        assertThat(policy.check("http://split-horizon.test/hook").allowed()).isFalse();
    }

    @Test
    void aPublicDestinationIsAllowedAndItsResolvedAddressesAreReturned() throws UnknownHostException {
        EgressDecision decision = policyResolvingTo("93.184.216.34").check("https://merchant.example/hook");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isNull();
        // Returned so the caller can pin the connection to what was actually validated —
        // re-resolving the name at connect time would reopen a DNS-rebinding window
        // between the check and the request.
        assertThat(decision.resolvedAddresses()).containsExactly(InetAddress.getByName("93.184.216.34"));
    }

    @ParameterizedTest(name = "blocks scheme {0}")
    @ValueSource(strings = {
            "file:///etc/passwd",
            "gopher://evil.test/_payload",
            "ftp://evil.test/file",
            "jar:http://evil.test/a.jar!/",
    })
    void aNonHttpSchemeIsRefused(String url) {
        assertThat(policyResolvingTo("93.184.216.34").check(url).allowed()).isFalse();
    }

    @Test
    void plainHttpIsRefusedWhenHttpsIsRequired() {
        EgressPolicy strict = new EgressPolicy(properties(true, List.of()),
                resolverReturning("93.184.216.34"));

        assertThat(strict.check("http://merchant.example/hook").allowed()).isFalse();
        assertThat(strict.check("https://merchant.example/hook").allowed()).isTrue();
    }

    @Test
    void embeddedCredentialsAreRefused() {
        assertThat(policyResolvingTo("93.184.216.34")
                .check("http://user:pass@merchant.example/hook").allowed()).isFalse();
    }

    @Test
    void anUnparseableOrHostlessUrlIsRefused() {
        EgressPolicy policy = policyResolvingTo("93.184.216.34");

        assertThat(policy.check("not a url at all").allowed()).isFalse();
        assertThat(policy.check("http:///hook").allowed()).isFalse();
        assertThat(policy.check("/relative/hook").allowed()).isFalse();
    }

    @Test
    void anUnresolvableHostIsRefusedRatherThanAttempted() {
        EgressPolicy policy = new EgressPolicy(properties(false, List.of()), host -> {
            throw new UnknownHostException(host);
        });

        EgressDecision decision = policy.check("http://does-not-exist.invalid/hook");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("resolved");
    }

    @Test
    void anExplicitlyAllowListedHostBypassesTheRangeChecks() {
        // The single, deliberate exemption: local development and this repository's own
        // integration tests deliver to localhost sinks. Weakening EgressPolicy itself for
        // that would weaken it in production too.
        EgressPolicy policy = new EgressPolicy(properties(false, List.of("localhost")),
                resolverReturning("127.0.0.1"));

        assertThat(policy.check("http://localhost:9099/hook").allowed()).isTrue();
        // ...and only for the listed host. Everything else is still refused.
        assertThat(policy.check("http://127.0.0.1:9099/hook").allowed()).isFalse();
    }

    @Test
    void theAllowListIsEmptyByDefaultSoNothingIsExemptUnlessConfigured() {
        assertThat(properties(true, null).allowedHosts()).isEmpty();
        assertThat(policyResolvingTo("127.0.0.1").check("http://localhost/hook").allowed()).isFalse();
    }
}
