package com.paymentflow.agentic.catalog;

import com.paymentflow.agentic.config.AgenticProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Seeds a demo catalogue for one configured merchant, once, if that merchant has none.
 *
 * <p><b>Why a runner and not a Flyway migration.</b> A migration is immutable and shared by
 * every environment, so seeding there would mean baking a merchant UUID into a file that can
 * never be corrected — and the demo merchant differs per environment because it is whichever
 * merchant the configured API key belongs to. Seeding is therefore configuration-driven and
 * inert by default: with no {@code demo.merchant-id} set, this class does nothing at all.
 *
 * <p>Idempotent by existence check rather than by upsert. If the merchant already has any
 * product, nothing happens — including on a restart, and including if an operator has since
 * edited the catalogue through the API. Re-seeding over someone's edits would be the more
 * surprising behaviour.
 *
 * <p>Prices are in paise and the currency is INR throughout, matching the policy defaults.
 */
@Component
public class CatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeeder.class);

    /** This extension is test-mode-only, enforced in the schema; the seed says so explicitly. */
    private static final String MODE = "test";

    private final AgenticProperties properties;
    private final ProductRepository productRepository;

    public CatalogSeeder(AgenticProperties properties, ProductRepository productRepository) {
        this.properties = properties;
        this.productRepository = productRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AgenticProperties.Demo demo = properties.demo();
        if (!demo.isSeedingEnabled()) {
            log.debug("Demo catalogue seeding is disabled (no paymentflow.agentic.demo.merchant-id configured).");
            return;
        }

        UUID merchantId;
        try {
            merchantId = UUID.fromString(demo.merchantId().trim());
        } catch (IllegalArgumentException e) {
            // A malformed id is a configuration mistake worth naming loudly, but it must not
            // stop the service booting: the provider-decision endpoint and the platform client
            // are entirely independent of whether a demo catalogue exists.
            log.warn("paymentflow.agentic.demo.merchant-id is not a UUID; skipping catalogue seeding.");
            return;
        }

        if (productRepository.existsByMerchantIdAndMode(merchantId, MODE)) {
            log.info("Merchant {} already has a catalogue; not seeding.", merchantId);
            return;
        }

        List<Product> seed = demoCatalogue(merchantId);
        productRepository.saveAll(seed);
        log.info("Seeded {} demo products for merchant {} in {} mode.", seed.size(), merchantId, MODE);
    }

    /**
     * The <b>Knitt</b> catalogue — a small premium-basics clothing line. Knitt
     * ({@code mock-project/knitt}) is the demo merchant that integrates PaymentFlow: its
     * storefront sells these exact SKUs, and this seed is what makes the agent shop the same
     * catalogue the storefront shows. Enough categories for a cross-sell to be meaningful,
     * enough price spread for the policy thresholds to be exercised by realistic purchases
     * (every item is well under the per-payment cap; the jacket and sweatshirt leave room for
     * a refund that trips the approval threshold), and small enough to read in one screen.
     *
     * <p>Prices are in paise, currency INR, matching the policy defaults.
     */
    private static List<Product> demoCatalogue(UUID merchantId) {
        return List.of(
                product(merchantId, "KNT-TEE-01", "Oversized Cotton Tee",
                        "A heavyweight 240gsm combed-cotton tee with a boxy, relaxed fit and a ribbed collar.",
                        "tops", 199000, 120),
                product(merchantId, "KNT-POL-01", "Knit Polo",
                        "A short-sleeve knitted polo in a breathable cotton-linen blend with a three-button placket.",
                        "tops", 279000, 90),
                product(merchantId, "KNT-HOD-01", "Essential Hoodie",
                        "A midweight loopback-cotton hoodie with a double-layer hood and a kangaroo pocket.",
                        "sweats", 349000, 80),
                product(merchantId, "KNT-SWT-01", "Heavyweight Sweatshirt",
                        "A 380gsm brushed-back crewneck that keeps its shape wash after wash.",
                        "sweats", 429000, 60),
                product(merchantId, "KNT-CRG-01", "Relaxed Cargo Pants",
                        "Cotton-twill cargos with a tapered leg, six pockets and a webbing belt.",
                        "bottoms", 399000, 70),
                product(merchantId, "KNT-JKT-01", "Minimal Knit Jacket",
                        "An unlined full-zip jacket in a dense milano knit — the warm layer that still looks tailored.",
                        "outerwear", 699000, 40),
                product(merchantId, "KNT-BNE-01", "Ribbed Beanie",
                        "A snug merino-blend beanie with a short turn-back cuff.",
                        "accessories", 129000, 150),
                product(merchantId, "KNT-CAP-01", "6-Panel Cap",
                        "A washed-cotton six-panel cap with an embroidered eyelet and a brass slider.",
                        "accessories", 169000, 110),
                product(merchantId, "KNT-TOT-01", "Canvas Tote",
                        "A 16oz cotton-canvas tote with reinforced handles and an internal slip pocket.",
                        "accessories", 149000, 130),
                product(merchantId, "KNT-SCK-01", "Everyday Sock 3-Pack",
                        "Three pairs of cushioned-sole ribbed crew socks in a combed-cotton blend.",
                        "accessories", 89000, 200));
    }

    private static Product product(UUID merchantId, String sku, String name, String description,
                                   String category, long priceMinor, int inventory) {
        return Product.create(merchantId, MODE, sku, name, description, category, priceMinor, "INR", inventory, null);
    }
}
