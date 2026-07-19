variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "ecr_repository_arns" {
  description = "Every service's ECR repository ARN (from the ecr module) — scopes both the ECS execution role's pull permission and the GitHub Actions role's push permission."
  type        = list(string)
}

variable "secret_arns" {
  description = "Every Secrets Manager secret ARN the ECS task execution role needs to resolve into container env vars at launch (from the secrets module)."
  type        = list(string)
}

variable "github_repository" {
  description = "GitHub \"owner/repo\" slug allowed to assume the CI/CD role via OIDC (e.g. \"IsaHaameem/cloud-native-payment-processing-platform\")."
  type        = string
}

variable "ecs_cluster_arn" {
  description = "Scopes the GitHub Actions role's ecs:UpdateService/DescribeServices permissions to this cluster's services only (M12's CD workflow calls `aws ecs update-service --force-new-deployment` per service)."
  type        = string
}

variable "msk_cluster_arn" {
  description = "Scopes the ECS task role's kafka-cluster:* IAM-auth permissions to this one MSK Serverless cluster (M12 — the 5 Kafka-touching services' task role needs this to connect at all, since MSK Serverless uses IAM SASL, not a username/password)."
  type        = string
}

variable "create_github_oidc_provider" {
  description = "Whether to create the token.actions.githubusercontent.com OIDC provider. AWS allows only one per account per URL — set to false and supply an existing provider ARN via a future variable if another stack in this account already created one."
  type        = bool
  default     = true
}

variable "tags" {
  type    = map(string)
  default = {}
}
