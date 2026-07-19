#
# Creates the S3 bucket (versioned, encrypted, public access blocked) and
# DynamoDB lock table that environments/dev's backend.tf points at. This is
# the one piece of infrastructure that must exist BEFORE any other root
# module can use a remote backend — a well-known Terraform bootstrapping
# problem, not something specific to this project.
#
# NOT applied this milestone (M11's explicit "do not actually create AWS
# resources" — D62): this code exists, is fmt/validate-clean, and is ready
# to `terraform apply` by hand exactly once whenever remote state is
# actually turned on, but doing so is a deliberate one-time manual action
# outside any milestone's normal apply flow, not something this or any
# future milestone should run as a matter of course.
#

resource "aws_s3_bucket" "state" {
  bucket = var.state_bucket_name

  tags = var.tags
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_dynamodb_table" "locks" {
  name         = var.lock_table_name
  billing_mode = "PAY_PER_REQUEST" # no fixed cost while idle — matches this project's cost-conscious stance
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  tags = var.tags
}
