#
# Deliberately local state (no backend block) — this is the one Terraform
# root in the whole project that CANNOT use the S3 backend it creates,
# for the obvious chicken-and-egg reason. Applied exactly once, manually,
# before environments/dev's remote backend can be initialized for real.
#
# NOT applied as part of M11 (see this directory's main.tf header comment
# and D62) — "do not actually create AWS resources" this milestone.
#
terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}
