variable "project_name" {
  description = "Short project identifier used in every resource name (e.g. \"paymentflow\")."
  type        = string
}

variable "environment" {
  description = "Deployment environment name (e.g. \"dev\"), used in resource names and tags."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
}

variable "availability_zones" {
  description = "Availability zones to spread subnets across. Length must match the subnet CIDR lists."
  type        = list(string)
}

variable "public_subnet_cidrs" {
  description = "One CIDR per availability zone, for public (ALB-facing) subnets."
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "One CIDR per availability zone, for private (ECS/RDS/ElastiCache/MSK-facing) subnets."
  type        = list(string)
}

variable "single_nat_gateway" {
  description = <<-EOT
    true: one shared NAT Gateway for every private subnet (lower cost, a single
    cross-AZ point of failure for outbound internet from private subnets).
    false: one NAT Gateway per AZ (higher availability, proportionally higher
    hourly + data-processing cost). Defaults to true — this project's own
    stated cost-consciousness (see PROJECT_CONTEXT.md's Risks section)
    outweighs NAT-level HA for a portfolio-scale workload; flip per-environment
    if a future environment needs it.
  EOT
  type        = bool
  default     = true
}

variable "tags" {
  description = "Common tags merged onto every resource this module creates."
  type        = map(string)
  default     = {}
}
