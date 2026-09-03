package dev.paymentflow.resources;

import dev.paymentflow.OffsetPage;
import dev.paymentflow.RequestOptions;
import dev.paymentflow.internal.Transport;
import dev.paymentflow.model.DecisionLogEntryResponse;
import dev.paymentflow.model.Operations;
import dev.paymentflow.model.SimulationOverrideResponse;
import dev.paymentflow.model.TestCardResponse;

import java.util.List;
import java.util.Map;

/**
 * {@code client.testHelpers()} — the sandbox controls. Six operations that only exist in test
 * mode, grouped so the call site signals it: none of this works with a live key.
 *
 * <p>The mode is decided by the key alone. This SDK has no {@code mode} option and will not get
 * one — a switch that appeared to move a client between test and live would be a lie, because
 * the platform ignores everything except which key was presented.
 */
public final class TestHelpers extends Resource {

    public TestHelpers(Transport transport) {
        super(transport);
    }

    /** What {@link #createSimulationOverride} accepts. */
    public static final class SimulationOverrideParams {

        String scenario;
        String declineCode;
        String errorCode;
        Long latencyMs;
        Long remainingCount;
        Long durationSeconds;

        /** Which behaviour to force. Required. See {@code CREATE_SIMULATION_OVERRIDE_REQUEST_SCENARIO_VALUES}. */
        public SimulationOverrideParams scenario(String scenario) {
            this.scenario = scenario;
            return this;
        }

        public SimulationOverrideParams declineCode(String declineCode) {
            this.declineCode = declineCode;
            return this;
        }

        public SimulationOverrideParams errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public SimulationOverrideParams latencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        /** How many authorizations the override applies to. Supply this or {@link #durationSeconds}. */
        public SimulationOverrideParams remainingCount(long remainingCount) {
            this.remainingCount = remainingCount;
            return this;
        }

        public SimulationOverrideParams durationSeconds(long durationSeconds) {
            this.durationSeconds = durationSeconds;
            return this;
        }
    }

    public static SimulationOverrideParams simulationOverrideParams() {
        return new SimulationOverrideParams();
    }

    /** Lists the seeded test cards and what each one does. A plain list; the catalogue is small and fixed. */
    public List<TestCardResponse> listCards(RequestOptions options) {
        return sendList(Operations.LIST_TEST_CARDS, null, null, opts(options), TestCardResponse.class);
    }

    public List<TestCardResponse> listCards() {
        return listCards(null);
    }

    /** Lists authorization decisions the sandbox made, and why. Offset-paginated (D139). */
    public OffsetPage<DecisionLogEntryResponse> listDecisions(Integer size, List<String> sort, RequestOptions options) {
        Map<String, Object> query = query().put("size", size).put("sort", sort).build();
        return listOffset(Operations.LIST_SANDBOX_DECISIONS, null, query, opts(options),
                DecisionLogEntryResponse.class);
    }

    public OffsetPage<DecisionLogEntryResponse> listDecisions() {
        return listDecisions(null, null, null);
    }

    /** Lists the decisions made for one payment. A plain list — one payment's decisions are few. */
    public List<DecisionLogEntryResponse> listDecisionsForPayment(String paymentId, RequestOptions options) {
        return sendList(Operations.LIST_SANDBOX_DECISIONS_FOR_PAYMENT, Map.of("paymentId", paymentId), null,
                opts(options), DecisionLogEntryResponse.class);
    }

    public List<DecisionLogEntryResponse> listDecisionsForPayment(String paymentId) {
        return listDecisionsForPayment(paymentId, null);
    }

    /** Forces a behaviour for subsequent authorizations, replacing any active override. */
    public SimulationOverrideResponse createSimulationOverride(SimulationOverrideParams params, RequestOptions options) {
        Object body = body()
                .put("scenario", params.scenario)
                .put("declineCode", params.declineCode)
                .put("errorCode", params.errorCode)
                .put("latencyMs", params.latencyMs)
                .put("remainingCount", params.remainingCount)
                .put("durationSeconds", params.durationSeconds)
                .build();
        return send(Operations.CREATE_SIMULATION_OVERRIDE, null, null, body, opts(options),
                SimulationOverrideResponse.class);
    }

    public SimulationOverrideResponse createSimulationOverride(SimulationOverrideParams params) {
        return createSimulationOverride(params, null);
    }

    /** Retrieves the active override. */
    public SimulationOverrideResponse retrieveActiveSimulationOverride(RequestOptions options) {
        return send(Operations.GET_ACTIVE_SIMULATION_OVERRIDE, null, null, null, opts(options),
                SimulationOverrideResponse.class);
    }

    public SimulationOverrideResponse retrieveActiveSimulationOverride() {
        return retrieveActiveSimulationOverride(null);
    }

    /** Revokes the active override. The API returns 204. */
    public void revokeActiveSimulationOverride(RequestOptions options) {
        sendVoid(Operations.REVOKE_ACTIVE_SIMULATION_OVERRIDE, null, null, opts(options));
    }

    public void revokeActiveSimulationOverride() {
        revokeActiveSimulationOverride(null);
    }
}
