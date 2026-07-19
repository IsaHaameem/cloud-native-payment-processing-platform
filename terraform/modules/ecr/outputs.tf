output "repository_urls" {
  description = "ECR repository URL per service, keyed by service name."
  value       = { for name, repo in aws_ecr_repository.this : name => repo.repository_url }
}

output "repository_arns" {
  description = "ECR repository ARN per service, keyed by service name — used by the IAM module to scope pull/push permissions."
  value       = { for name, repo in aws_ecr_repository.this : name => repo.arn }
}
