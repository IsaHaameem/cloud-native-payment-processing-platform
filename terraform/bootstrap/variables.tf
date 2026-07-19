variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "state_bucket_name" {
  description = "Must be globally unique across all of AWS — S3 bucket names share one namespace."
  type        = string
  default     = "paymentflow-terraform-state"
}

variable "lock_table_name" {
  type    = string
  default = "paymentflow-terraform-locks"
}

variable "tags" {
  type = map(string)
  default = {
    Project   = "paymentflow"
    ManagedBy = "terraform"
    Purpose   = "terraform-remote-state-bootstrap"
  }
}
