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
