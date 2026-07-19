variable "project_name" {
  type    = string
  default = "paymentflow"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "availability_zones" {
  description = "Two AZs — enough for the ALB/private-subnet HA this environment actually benefits from, without paying for a third (RDS/ElastiCache/MSK Serverless are all single-AZ-effective here anyway; see each module's own defaults)."
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b"]
}

variable "public_subnet_cidrs" {
  type    = list(string)
  default = ["10.0.0.0/24", "10.0.1.0/24"]
}

variable "private_subnet_cidrs" {
  type    = list(string)
  default = ["10.0.10.0/24", "10.0.11.0/24"]
}

variable "github_repository" {
  description = "\"owner/repo\" slug allowed to assume the GitHub Actions ECR-push role via OIDC."
  type        = string
  default     = "IsaHaameem/cloud-native-payment-processing-platform"
}

variable "alb_certificate_arn" {
  description = "ACM certificate ARN for the ALB's HTTPS listener. null (default) — no HTTPS listener is created until Route53/ACM issuance is in scope."
  type        = string
  default     = null
}

variable "image_tag" {
  description = "Image tag every ECS task definition references (e.g. \"latest\" or a git SHA, matching M10's CI tagging scheme). No image with this tag exists in any ECR repository yet (M10's CI still only builds/pushes to GHCR, D59) — real tasks would fail to start until a real push lands one (see Known Issues)."
  type        = string
  default     = "latest"
}
