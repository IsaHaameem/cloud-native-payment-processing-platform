package com.paymentflow.notification.egress;

import com.paymentflow.notification.config.WebhookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The SSRF guard (M18.5, §4.9/R5). Webhook delivery is the one place this platform makes
 * an outbound HTTP request to a destination a *merchant* chose, from inside the VPC — so
 * an unguarded delivery pipeline is a request-forgery primitive pointed at the platform's
 * own network and, in AWS, at the instance metadata service.
 *
 * <p>Deliberately a pure, dependency-free function over a URL plus DNS, so the full
 * hostile-URL table can be tested exhaustively without a network — the same discipline
 * {@code ApiKeyFormat} and {@code DecisionEngine} are held to.
 *
 * <p>What it refuses, and why each entry is not redundant with the others:
 * <ul>
 *   <li><b>Non-HTTP(S) schemes</b> — {@code file:}, {@code gopher:}, {@code ftp:} are not
 *       webhook transports and several are classic SSRF escalation vectors.</li>
 *   <li><b>Loopback</b> ({@code 127.0.0.0/8}, {@code ::1}) — reaches this service's own
 *       actuator endpoints and every co-located process.</li>
 *   <li><b>Link-local</b> ({@code 169.254.0.0/16}, {@code fe80::/10}) — contains
 *       {@code 169.254.169.254}, the cloud instance-metadata address, which is the single
 *       highest-value SSRF target in any cloud deployment.</li>
 *   <li><b>Private ranges</b> ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16},
 *       {@code fc00::/7}) — every other service in the VPC.</li>
 *   <li><b>Wildcard, multicast, and "any" addresses</b> — {@code 0.0.0.0} routes to
 *       localhost on several stacks.</li>
 *   <li><b>IPv4-mapped and IPv4-compatible IPv6</b> ({@code ::ffff:127.0.0.1}) — the same
 *       forbidden address wearing an IPv6 costume, and the check most often missed
 *       because a naive implementation tests the textual form rather than the bytes.</li>
 *   <li><b>Every</b> address a hostname resolves to, not merely the first — a hostile DNS
 *       record can return one public and one private address, and connecting to "the
 *       host" may reach either.</li>
 * </ul>
 *
 * <p>An operator-configured allow-list of hostnames bypasses the private-range checks.
 * That exists solely so local development and this repository's own integration tests can
 * deliver to {@code localhost} sinks; it is empty by default, and anything on it is
 * trusted deliberately rather than by accident.
 */
@Component
public class EgressPolicy {

    private static final Logger log = LoggerFactory.getLogger(EgressPolicy.class);
    private static final String HTTPS = "https";
    private static final String HTTP = "http";

    private final WebhookProperties properties;
    private final HostResolver hostResolver;

    public EgressPolicy(WebhookProperties properties, HostResolver hostResolver) {
        this.properties = properties;
        this.hostResolver = hostResolver;
    }

    /** DNS lookup, injected so the hostile-URL table can be tested without a network or a DNS server. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * Checks a destination immediately before connecting. The returned decision carries
     * the resolved addresses so the caller connects to <em>those</em> rather than
     * re-resolving the name — see {@link EgressDecision}.
     */
    public EgressDecision check(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return EgressDecision.deny("The destination URL could not be parsed.");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!HTTPS.equals(scheme) && !HTTP.equals(scheme)) {
            return EgressDecision.deny("Only http and https destinations are permitted.");
        }
        if (properties.requireHttps() && !HTTPS.equals(scheme)) {
            return EgressDecision.deny("Only https destinations are permitted.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return EgressDecision.deny("The destination URL has no host.");
        }
        if (uri.getUserInfo() != null) {
            return EgressDecision.deny("The destination URL must not contain embedded credentials.");
        }

        String normalizedHost = stripIpv6Brackets(host).toLowerCase(Locale.ROOT);
        InetAddress[] resolved;
        try {
            resolved = hostResolver.resolve(normalizedHost);
        } catch (UnknownHostException e) {
            return EgressDecision.deny("The destination host could not be resolved.");
        }
        if (resolved == null || resolved.length == 0) {
            return EgressDecision.deny("The destination host could not be resolved.");
        }

        boolean allowListed = properties.allowedHosts().stream()
                .anyMatch(allowed -> allowed.equalsIgnoreCase(normalizedHost));

        List<InetAddress> addresses = new ArrayList<>(List.of(resolved));
        if (!allowListed) {
            for (InetAddress address : addresses) {
                // Every resolved address, not just the first: a hostile record can mix a
                // public address with a private one, and "connecting to the host" may
                // reach either.
                if (isBlocked(address)) {
                    log.warn("Refusing webhook delivery to {} — it resolves to a blocked address.", normalizedHost);
                    return EgressDecision.deny("The destination resolves to a blocked address range.");
                }
            }
        }
        return EgressDecision.allow(addresses);
    }

    /** True for any address a webhook must never be delivered to. */
    public static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet6Address ipv6) {
            // ::ffff:127.0.0.1 and ::127.0.0.1 wear an IPv6 costume over a forbidden IPv4
            // address; java.net's own predicates above do not see through either form, so
            // the embedded address is extracted and re-checked on its own.
            InetAddress embedded = embeddedIpv4(ipv6);
            if (embedded != null) {
                return isBlocked(embedded);
            }
            // fc00::/7 — unique local addresses. isSiteLocalAddress() covers only the
            // deprecated fec0::/10 for IPv6, so this range must be checked explicitly.
            byte[] bytes = ipv6.getAddress();
            return (bytes[0] & 0xFE) == 0xFC;
        }
        if (address instanceof Inet4Address) {
            byte[] bytes = address.getAddress();
            int first = bytes[0] & 0xFF;
            // 100.64.0.0/10 (carrier-grade NAT) and 0.0.0.0/8 are neither "site local" nor
            // "any local" by java.net's definitions, but neither belongs on the public
            // internet and both have been used to reach infrastructure.
            if (first == 0) {
                return true;
            }
            return first == 100 && (bytes[1] & 0xC0) == 0x40;
        }
        return false;
    }

    /** The embedded IPv4 address of an IPv4-mapped or IPv4-compatible IPv6 address, or {@code null}. */
    private static InetAddress embeddedIpv4(Inet6Address address) {
        byte[] bytes = address.getAddress();
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return null;
            }
        }
        boolean mapped = (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
        boolean compatible = bytes[10] == 0 && bytes[11] == 0;
        if (!mapped && !compatible) {
            return null;
        }
        try {
            return InetAddress.getByAddress(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static String stripIpv6Brackets(String host) {
        if (host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
