#
# Secrets Manager containers for the platform's stored credentials. Values
# are Terraform-generated (random_password / tls_private_key) rather than
# left for manual out-of-band population — Terraform state itself is the
# thing this project's remote-state decision (S3 + encryption + DynamoDB
# lock) already protects, so a generated-then-stored secret is the standard,
# idiomatic pattern here, not a shortcut. Nothing in this module is ever
# printed to a plan/apply summary in plain text (every value marked
# sensitive) and no application code changes: identity-service already reads
# `paymentflow.security.jwt.private-key`/`.public-key` when set (D18), and
# every other service already reads `SPRING_DATASOURCE_PASSWORD`/
# `SPRING_DATA_REDIS_PASSWORD` from its environment — M12's task definitions
# are what will actually wire these secret ARNs into those env vars.
#

resource "random_password" "rds_master" {
  length  = 32
  special = false # simplest safe superset of characters the Postgres driver/JDBC URL never need to escape
}

resource "aws_secretsmanager_secret" "rds_master_credentials" {
  name = "${var.project_name}/${var.environment}/rds/master-credentials"

  tags = merge(var.tags, { Name = "${var.project_name}-${var.environment}-rds-master-credentials" })
}

resource "aws_secretsmanager_secret_version" "rds_master_credentials" {
  secret_id = aws_secretsmanager_secret.rds_master_credentials.id
  secret_string = jsonencode({
    username = "paymentflow"
    password = random_password.rds_master.result
  })
}

resource "random_password" "redis_auth_token" {
  length  = 32
  special = false # ElastiCache AUTH tokens reject several special characters outright
}

resource "aws_secretsmanager_secret" "redis_auth_token" {
  name = "${var.project_name}/${var.environment}/redis/auth-token"

  tags = merge(var.tags, { Name = "${var.project_name}-${var.environment}-redis-auth-token" })
}

resource "aws_secretsmanager_secret_version" "redis_auth_token" {
  secret_id     = aws_secretsmanager_secret.redis_auth_token.id
  secret_string = random_password.redis_auth_token.result
}

# identity-service's RS256 signing keypair (D15/D18) — every other service
# validates against identity's public JWKS endpoint and never needs this
# secret directly.
resource "tls_private_key" "jwt_signing_key" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "aws_secretsmanager_secret" "jwt_signing_key" {
  name = "${var.project_name}/${var.environment}/identity/jwt-signing-key"

  tags = merge(var.tags, { Name = "${var.project_name}-${var.environment}-jwt-signing-key" })
}

resource "aws_secretsmanager_secret_version" "jwt_signing_key" {
  secret_id = aws_secretsmanager_secret.jwt_signing_key.id
  secret_string = jsonencode({
    # identity-service's PemUtils.parsePrivateKey uses PKCS8EncodedKeySpec, which
    # requires PKCS#8 ("BEGIN PRIVATE KEY"), not the tls provider's default
    # private_key_pem attribute (PKCS#1, "BEGIN RSA PRIVATE KEY") — the two are
    # different DER structures, not just a header-text difference. Found as a
    # real bug during the Infrastructure Recovery E2E test (identity-service
    # threw "algid parse error, not a sequence" parsing the PKCS#1 PEM as PKCS#8).
    private_key_pem = tls_private_key.jwt_signing_key.private_key_pem_pkcs8
    public_key_pem  = tls_private_key.jwt_signing_key.public_key_pem
  })
}

# The HMAC secret InternalContextFilter verifies a signed internal context against
# (D100) — one shared value all ten services read as PAYMENTFLOW_INTERNAL_CONTEXT_SECRET.
# Not wired by M11/M12 (D100 postdates them); every one of the ten services' own
# application.yaml already falls back to the identical insecure literal
# ("dev-only-insecure-shared-secret-change-me") if this is ever unset, so a real value
# here is what makes internal-context verification a real trust boundary in this
# environment rather than every service silently agreeing on the same public default.
resource "random_password" "internal_context_secret" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "internal_context_secret" {
  name = "${var.project_name}/${var.environment}/internal-context/secret"

  tags = merge(var.tags, { Name = "${var.project_name}-${var.environment}-internal-context-secret" })
}

resource "aws_secretsmanager_secret_version" "internal_context_secret" {
  secret_id     = aws_secretsmanager_secret.internal_context_secret.id
  secret_string = random_password.internal_context_secret.result
}

# notification-service's webhook signing-secret-at-rest encryption key
# (PAYMENTFLOW_WEBHOOK_SECRET_ENCRYPTION_KEY) — notification-service only; no other
# service reads it. Same "not wired by M11/M12, falls back to an insecure literal"
# situation as internal_context_secret above.
resource "random_password" "webhook_secret_encryption_key" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "webhook_secret_encryption_key" {
  name = "${var.project_name}/${var.environment}/notification/webhook-secret-encryption-key"

  tags = merge(var.tags, { Name = "${var.project_name}-${var.environment}-webhook-secret-encryption-key" })
}

resource "aws_secretsmanager_secret_version" "webhook_secret_encryption_key" {
  secret_id     = aws_secretsmanager_secret.webhook_secret_encryption_key.id
  secret_string = random_password.webhook_secret_encryption_key.result
}

# ── Agentic Commerce (Project 3) — human-provided credentials ───────────────────────
#
# Three real, external credentials agentic-commerce-service needs that Terraform
# cannot generate, unlike everything above: a live PaymentFlow API key (which does
# not exist until AFTER this environment is deployed and a merchant is seeded through
# it — a genuine chicken-and-egg), and the LLM provider keys (Anthropic/OpenAI),
# which are third-party account credentials, not values this project mints.
#
# Terraform creates the secret CONTAINER and one placeholder version — just enough
# that the ECS task can actually launch (the app's own documented behaviour for a
# blank key: identity-service-style "refuse the one thing that needs it, not the
# whole process" for the platform key, and a deterministic scripted LLM client in
# place of a real model call — see agentic-commerce-service's own application.yaml).
#
# The placeholder is a single space, not an empty string. Confirmed against a live
# apply: AWS's PutSecretValue rejects secret_string = "" outright —
# `InvalidRequestException: You must provide either SecretString or SecretBinary` —
# an actually-empty string doesn't satisfy the API's "a value was provided" check.
# A single space does (non-zero length), while remaining exactly as blank to the
# application as an empty string would have been: every one of the three properties
# this feeds is read through `String.isBlank()` (AgenticProperties.Llm.isConfigured,
# .Platform's equivalent — verified directly in that file, not assumed), and
# isBlank() is true for a whitespace-only string exactly as it is for "". No
# credential-shaped value is ever written; " " cannot be mistaken for a real key,
# is never dereferenced as one, and could never be logged as one.
#
# `lifecycle.ignore_changes` on the version is what makes this safe to populate for
# real afterwards: once a human runs `aws secretsmanager put-secret-value` with the
# genuine key, a later `terraform apply` will never see that as drift and revert it
# back to the placeholder, because Terraform is told never to look at secret_string
# again after the first apply.
resource "aws_secretsmanager_secret" "agentic_platform_api_key" {
  name = "${var.project_name}/${var.environment}/agentic/platform-api-key"

  tags = merge(var.tags, { Name = "${var.project_name}-${var.environment}-agentic-platform-api-key" })
}

resource "aws_secretsmanager_secret_version" "agentic_platform_api_key" {
  secret_id     = aws_secretsmanager_secret.agentic_platform_api_key.id
  secret_string = " " # placeholder (blank to the app; AWS rejects a truly empty string) — populate with a real sk_... key after seeding, via the AWS CLI/console, never here

  lifecycle {
    ignore_changes = [secret_string]
  }
}

resource "aws_secretsmanager_secret" "agentic_anthropic_api_key" {
  name = "${var.project_name}/${var.environment}/agentic/anthropic-api-key"

  tags = merge(var.tags, { Name = "${var.project_name}-${var.environment}-agentic-anthropic-api-key" })
}

resource "aws_secretsmanager_secret_version" "agentic_anthropic_api_key" {
  secret_id     = aws_secretsmanager_secret.agentic_anthropic_api_key.id
  secret_string = " " # placeholder (blank to the app; AWS rejects a truly empty string) — the service falls back to its scripted LLM client, never fails to start

  lifecycle {
    ignore_changes = [secret_string]
  }
}

resource "aws_secretsmanager_secret" "agentic_openai_api_key" {
  name = "${var.project_name}/${var.environment}/agentic/openai-api-key"

  tags = merge(var.tags, { Name = "${var.project_name}-${var.environment}-agentic-openai-api-key" })
}

resource "aws_secretsmanager_secret_version" "agentic_openai_api_key" {
  secret_id     = aws_secretsmanager_secret.agentic_openai_api_key.id
  secret_string = " " # placeholder (blank to the app; AWS rejects a truly empty string) — only read at all when AGENTIC_LLM_PROVIDER=openai

  lifecycle {
    ignore_changes = [secret_string]
  }
}
