variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "certificate_arn" {
  description = "ACM certificate ARN for the HTTPS listener. null (default) skips creating the HTTPS listener entirely — Route53/ACM issuance isn't in this milestone's scope, so M11 only prepares the ALB shell; a later milestone supplies a real certificate."
  type        = string
  default     = null
}

variable "tags" {
  type    = map(string)
  default = {}
}
