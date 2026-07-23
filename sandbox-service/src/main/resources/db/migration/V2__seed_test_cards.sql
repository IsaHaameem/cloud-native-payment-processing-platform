-- Seeds the test-card catalogue (§8.1) as versioned reference data — never hand-inserted,
-- so the catalogue and the docs rendered from it (M17.8) cannot drift from what a real
-- call actually produces. Two cards beyond the documented fifteen (pm_card_lostCard,
-- pm_card_issuerUnavailable) close two of M17's requested failure-simulation scenarios
-- that had no existing card to represent (lost card, issuer unavailable).
--
-- pm_card_disputed's documented behaviour ("captures, then raises a dispute event") is
-- seeded as a plain approve+capture: this platform has no dispute/chargeback concept
-- anywhere in V2 (§15 lists it as unbuilt, beyond V2's scope) — there is no event to
-- raise. Recorded honestly in the description rather than silently promising a dispute
-- event M17 cannot emit.

insert into test_cards (token, brand, outcome, decline_code, error_code, latency_ms,
                         capture_behaviour, refund_behaviour, deferred_delay_ms, description) values
('pm_card_visa',               'visa',       'APPROVE', null,               null,                  0,    'SUCCEED', 'SUCCEED', null, 'Approves.'),
('pm_card_mastercard',         'mastercard', 'APPROVE', null,               null,                  0,    'SUCCEED', 'SUCCEED', null, 'Approves.'),
('pm_card_amex',               'amex',       'APPROVE', null,               null,                  0,    'SUCCEED', 'SUCCEED', null, 'Approves.'),
('pm_card_chargeDeclined',     'visa',       'DECLINE', 'card_declined',    null,                  0,    'SUCCEED', 'SUCCEED', null, 'Declines with card_declined.'),
('pm_card_insufficientFunds',  'visa',       'DECLINE', 'insufficient_funds', null,                0,    'SUCCEED', 'SUCCEED', null, 'Declines with insufficient_funds.'),
('pm_card_expired',            'visa',       'DECLINE', 'expired_card',     null,                  0,    'SUCCEED', 'SUCCEED', null, 'Declines with expired_card.'),
('pm_card_incorrectCvc',       'visa',       'DECLINE', 'incorrect_cvc',    null,                  0,    'SUCCEED', 'SUCCEED', null, 'Declines with incorrect_cvc.'),
('pm_card_fraudulent',         'visa',       'DECLINE', 'fraudulent',       null,                  0,    'SUCCEED', 'SUCCEED', null, 'Declines with fraudulent.'),
('pm_card_lostCard',           'visa',       'DECLINE', 'lost_card',        null,                  0,    'SUCCEED', 'SUCCEED', null, 'Declines with lost_card.'),
('pm_card_processingError',    'visa',       'ERROR',   null,               'processing_error',    0,    'SUCCEED', 'SUCCEED', null, 'Errors with processing_error.'),
('pm_card_issuerUnavailable',  'visa',       'ERROR',   null,               'issuer_unavailable',  0,    'SUCCEED', 'SUCCEED', null, 'Errors with issuer_unavailable.'),
('pm_card_authRequired',       'visa',       'REQUIRE_ACTION', null,        null,                  0,    'SUCCEED', 'SUCCEED', null, 'Requires an extra authentication step.'),
('pm_card_slow',               'visa',       'APPROVE', null,               null,                  5000, 'SUCCEED', 'SUCCEED', null, 'Approves after ~5s injected latency.'),
('pm_card_delayedSettlement',  'visa',       'APPROVE', null,               null,                  0,    'DEFER',   'SUCCEED', 5000, 'Authorizes now; captures asynchronously ~5s later.'),
('pm_card_captureFails',       'visa',       'APPROVE', null,               null,                  0,    'FAIL',    'SUCCEED', null, 'Authorizes, then fails at capture.'),
('pm_card_refundFails',        'visa',       'APPROVE', null,               null,                  0,    'SUCCEED', 'FAIL',    null, 'Captures, then fails at refund.'),
('pm_card_disputed',           'visa',       'APPROVE', null,               null,                  0,    'SUCCEED', 'SUCCEED', null, 'Captures normally. Dispute-event simulation is out of scope for V2 (no dispute/chargeback concept exists yet, §15).');
