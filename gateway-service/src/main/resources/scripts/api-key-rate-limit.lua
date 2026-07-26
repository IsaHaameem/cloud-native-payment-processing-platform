-- M20.5: per-key token bucket plus per-merchant daily quota, evaluated atomically.
--
-- One script rather than several round trips, because the read-modify-write on the bucket is
-- exactly the kind of thing that is correct on a developer's laptop and wrong under
-- concurrency: two gateway instances refilling the same bucket from a stale read would each
-- grant tokens the other already spent. Redis executes a script atomically, so the whole
-- decision is a single serialized step no matter how many gateways are running.
--
-- KEYS[1] bucket token count      KEYS[2] bucket last-refill timestamp      KEYS[3] daily quota counter
-- ARGV[1] refill rate (tokens/sec)  ARGV[2] burst capacity  ARGV[3] now (epoch seconds)
-- ARGV[4] tokens requested          ARGV[5] daily quota (0 = disabled)  ARGV[6] quota key TTL (seconds)
--
-- Returns { allowed, tokensRemaining, quotaUsed, retryAfterSeconds }
--   allowed           1 or 0
--   retryAfterSeconds seconds until a token is available; -1 means the *quota* refused it, and
--                     the caller computes the reset from the day boundary instead

local tokensKey    = KEYS[1]
local timestampKey = KEYS[2]
local quotaKey     = KEYS[3]

local rate      = tonumber(ARGV[1])
local capacity  = tonumber(ARGV[2])
local now       = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])
local quotaMax  = tonumber(ARGV[5])
local quotaTtl  = tonumber(ARGV[6])

-- Keys outlive a full refill so a returning caller sees an accurate bucket rather than a
-- silently reset one, but still expire — an idle key must not occupy memory forever.
local ttl = math.floor((capacity / rate) * 2) + 1

local lastTokens = tonumber(redis.call('get', tokensKey))
if lastTokens == nil then
  lastTokens = capacity
end
local lastRefreshed = tonumber(redis.call('get', timestampKey))
if lastRefreshed == nil then
  lastRefreshed = now
end

-- max(0, ...) guards against clock skew between gateway instances handing the bucket a
-- negative delta, which would otherwise *remove* tokens nobody spent.
local delta = math.max(0, now - lastRefreshed)
local filledTokens = math.min(capacity, lastTokens + (delta * rate))

local quotaUsed = tonumber(redis.call('get', quotaKey))
if quotaUsed == nil then
  quotaUsed = 0
end

-- The quota is checked before the bucket, and a quota refusal deliberately does not consume a
-- token: a caller who is already out of daily budget should not also be drained of burst
-- capacity they cannot use, or their first request tomorrow would be refused too.
if quotaMax > 0 and quotaUsed >= quotaMax then
  redis.call('setex', tokensKey, ttl, filledTokens)
  redis.call('setex', timestampKey, ttl, now)
  return { 0, math.floor(filledTokens), quotaUsed, -1 }
end

local allowed = filledTokens >= requested
local newTokens = filledTokens
local retryAfter = 0

if allowed then
  newTokens = filledTokens - requested
else
  -- Ceiling, so a client that waits exactly this long finds a whole token rather than 0.97 of
  -- one and is refused a second time.
  retryAfter = math.ceil((requested - filledTokens) / rate)
end

redis.call('setex', tokensKey, ttl, newTokens)
redis.call('setex', timestampKey, ttl, now)

-- The quota counts *admitted* requests only. Counting refused ones would let a client burn
-- their daily allowance on requests the platform never served.
if allowed and quotaMax > 0 then
  quotaUsed = redis.call('incr', quotaKey)
  if quotaUsed == 1 then
    redis.call('expire', quotaKey, quotaTtl)
  end
end

return { allowed and 1 or 0, math.floor(newTokens), quotaUsed, retryAfter }
