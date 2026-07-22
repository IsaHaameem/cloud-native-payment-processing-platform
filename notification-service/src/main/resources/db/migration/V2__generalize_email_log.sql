-- M15 (Decision 2): email_log now also carries identity-driven emails (verification,
-- password reset), which have no merchant context — merchant_id becomes nullable.
-- event_id needs no change: it was already a generic UUID, and identity.events'
-- envelope carries its own eventId exactly like payment.events does.
alter table email_log alter column merchant_id drop not null;
