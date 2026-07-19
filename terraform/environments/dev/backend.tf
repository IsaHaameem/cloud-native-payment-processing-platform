#
# Remote state (S3 + DynamoDB lock) — the settled Technology Stack decision
# (PROJECT_CONTEXT.md §2: "IaC | Terraform (remote state: S3 + DynamoDB
# lock)"). This backend targets the bucket/table terraform/bootstrap defines
# — but M11 deliberately does NOT apply bootstrap (see its own header
# comment and D62), so this bucket/table do not exist yet.
#
# Until bootstrap is applied in a later milestone, initialize this
# environment with the backend disabled entirely (state stays local, never
# touches AWS):
#
#   terraform init -backend=false
#
# Once bootstrap has been applied for real, drop -backend=false and run a
# normal `terraform init` (Terraform will offer to migrate local state into
# S3 automatically).
#
terraform {
  backend "s3" {
    bucket         = "paymentflow-terraform-state"
    key            = "environments/dev/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "paymentflow-terraform-locks"
    encrypt        = true
  }
}
