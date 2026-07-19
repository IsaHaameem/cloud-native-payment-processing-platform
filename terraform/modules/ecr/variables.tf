variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "service_names" {
  description = "Every microservice that gets its own ECR repository (the same 8 services M9's Dockerfile/docker-compose.yml and M10's CI matrix already build)."
  type        = list(string)
}

variable "image_tag_mutability" {
  description = "MUTABLE allows re-pushing the same tag (e.g. `latest`, matching M10's CI tagging scheme); IMMUTABLE is the stricter production default once a real release-tagging scheme exists."
  type        = string
  default     = "MUTABLE"
}

variable "untagged_image_expiry_days" {
  description = "Untagged images (superseded digests) are expired after this many days."
  type        = number
  default     = 7
}

variable "max_tagged_images_to_keep" {
  description = "How many tagged images to retain per repository before the oldest are expired."
  type        = number
  default     = 20
}

variable "tags" {
  type    = map(string)
  default = {}
}
