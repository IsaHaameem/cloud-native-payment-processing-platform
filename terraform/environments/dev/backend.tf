#
# Remote state (S3 + DynamoDB lock) — the settled Technology Stack decision
# (PROJECT_CONTEXT.md §2: "IaC | Terraform (remote state: S3 + DynamoDB
# lock)"). This backend targets the bucket/table terraform/bootstrap defines.
#
# `terraform/bootstrap` has now been applied by hand (bucket + lock table
# confirmed live in us-east-1) — this environment initializes normally:
#
#   terraform init
#
# Prior to bootstrap being applied, this environment was initialized with
# `terraform init -backend=false` (state local-only, never touching AWS);
# that mode remains available if the backend ever needs to be bypassed again.
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
