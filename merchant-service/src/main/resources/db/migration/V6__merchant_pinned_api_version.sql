-- M21.5 (D155): the merchant's pinned revision of the public API contract (§4.10).
--
-- Date-based versioning pins a merchant at their first call and keeps serving them that
-- shape forever, so the platform can ship improvements continuously without coordinating an
-- upgrade with every integrator. This column is where the pin lives.
--
-- On `merchants` rather than in a `merchant_settings` table, for exactly the reason M20.5
-- gave (D145): §4.6 assumed a settings table that was never built, and the value rides out
-- on the API-key verification response the gateway already resolves and caches on every
-- request. One column, no second round trip, no second cache to invalidate. If a fourth
-- unrelated setting ever appears, that is the moment to reconsider — not this one.
--
-- NULLABLE, and the null has a specific meaning that differs from D145's three: there it
-- meant "use the platform default". Here it means "this merchant has not called the public
-- API yet". The gateway writes the pin on the first request it authenticates, so null is a
-- transient state rather than a steady one — which is why there is no default value. A
-- default of the current version would silently pin every historical merchant to whatever
-- revision happened to be current on the day this migration ran, including merchants who
-- have never made a request and should be pinned when they do.
alter table merchants
    add column pinned_api_version varchar(10);

-- The wire form is a date, and the column is the only thing standing between a typo in a
-- future admin tool and a merchant whose every request 400s. `~` rather than a length check
-- because 'yyyy-MM-dd' is the *only* legal shape — the gateway parses this with
-- LocalDate.parse and treats a failure as "not pinned", so a malformed value would be
-- silently ignored rather than loudly rejected. Better to make it unstorable.
alter table merchants
    add constraint chk_merchants_pinned_api_version
        check (pinned_api_version is null or pinned_api_version ~ '^\d{4}-\d{2}-\d{2}$');
