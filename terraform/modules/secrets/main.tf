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
