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
  description = "GitHub \"owner/repo\" slug allowed to assume the CI deploy role via OIDC (e.g. \"IsaHaameem/cloud-native-payment-processing-platform\")."
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
