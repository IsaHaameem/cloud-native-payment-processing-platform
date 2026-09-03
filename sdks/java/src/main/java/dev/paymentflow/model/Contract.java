package dev.paymentflow.model;

/**
 * The contract this SDK was written against.
 *
 * <p>Hand-transcribed from {@code docs/openapi.yaml} and verified byte-for-fact against
 * {@code ../shared/fixtures/contract.json} by {@code ContractParityTest} — the same
 * language-neutral fixture the Node and Python SDKs assert against. The Node and Python
 * equivalents are emitted by {@code :sdks:shared}; a {@code JavaEmitter} is a possible future
 * refinement, and the parity test is what keeps this file honest until then.
 */
public final class Contract {

    private Contract() {}

    /**
     * The dated API revision this SDK sends as {@code PaymentFlow-Version} unless a caller
     * overrides it.
     *
     * <p>Deliberately not this package's own version. An SDK bug fix is a patch release against
     * an unchanged API, and a new API revision does not by itself change anything about this
     * package — conflating the two would make every contract revision a major bump.
     */
    public static final String API_VERSION = "2026-08-01";

    /** The published host. Overridable through {@code PaymentFlowOptions.baseUrl}. */
    public static final String DEFAULT_BASE_URL = "https://api.paymentflow.dev";

    /** The title of the contract this SDK implements. */
    public static final String API_TITLE = "PaymentFlow API";
}
