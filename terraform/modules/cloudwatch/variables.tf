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
  description = "CloudWatch Logs retention. 30 days by default — long enough to debug a recent incident, short enough not to accumulate indefinite storage cost for a portfolio-scale workload."
  type        = number
  default     = 30
}

variable "tags" {
  type    = map(string)
  default = {}
}
