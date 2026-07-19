variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "vpc_id" {
  description = "Needed for the Cloud Map private DNS namespace (service-to-service discovery — the AWS equivalent of docker-compose's service-name-as-DNS-name)."
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
