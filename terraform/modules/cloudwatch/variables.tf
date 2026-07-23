variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "service_names" {
  type = list(string)
}

variable "retention_days" {
  description = "CloudWatch Logs retention. 7 days by default — long enough to debug a recent demo/verification session, short enough not to accumulate indefinite storage cost for a portfolio-scale workload that isn't run continuously."
  type        = number
  default     = 7
}

variable "tags" {
  type    = map(string)
  default = {}
}
