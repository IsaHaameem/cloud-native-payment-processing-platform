variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "node_type" {
  type    = string
  default = "cache.t4g.micro"
}

variable "engine_version" {
  description = <<-EOT
    AWS ElastiCache's "redis" engine tops out at OSS Redis 7.1 (Redis Ltd's
    post-7.x license change is why AWS introduced the separate "valkey"
    engine for anything newer, rather than shipping Redis 8 under the
    "redis" engine name) — this is NOT the same major version as the local
    docker-compose stack's `redis:8-alpine` (see Known Issues). Functionally
    compatible for everything this platform actually uses (cache-aside,
    TTL, SETNX-based locks, token-bucket rate limiting); revisit if a future
    milestone needs a Redis 8-only feature.
  EOT
  type        = string
  default     = "7.1"
}

variable "auth_token" {
  description = "AUTH token (from the secrets module) — requires transit_encryption_enabled, set unconditionally below."
  type        = string
  sensitive   = true
}

variable "tags" {
  type    = map(string)
  default = {}
}
