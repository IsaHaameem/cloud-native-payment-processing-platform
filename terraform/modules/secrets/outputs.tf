output "rds_master_credentials_secret_arn" {
  value = aws_secretsmanager_secret.rds_master_credentials.arn
}

output "rds_master_username" {
  value = "paymentflow"
}

output "rds_master_password" {
  value     = random_password.rds_master.result
  sensitive = true
}

output "redis_auth_token_secret_arn" {
  value = aws_secretsmanager_secret.redis_auth_token.arn
}

output "redis_auth_token" {
  value     = random_password.redis_auth_token.result
  sensitive = true
}

output "jwt_signing_key_secret_arn" {
  value = aws_secretsmanager_secret.jwt_signing_key.arn
}

output "internal_context_secret_arn" {
  value = aws_secretsmanager_secret.internal_context_secret.arn
}

output "webhook_secret_encryption_key_secret_arn" {
  value = aws_secretsmanager_secret.webhook_secret_encryption_key.arn
}

output "agentic_platform_api_key_secret_arn" {
  value = aws_secretsmanager_secret.agentic_platform_api_key.arn
}

output "agentic_anthropic_api_key_secret_arn" {
  value = aws_secretsmanager_secret.agentic_anthropic_api_key.arn
}

output "agentic_openai_api_key_secret_arn" {
  value = aws_secretsmanager_secret.agentic_openai_api_key.arn
}
