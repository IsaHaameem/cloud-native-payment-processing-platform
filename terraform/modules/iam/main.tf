#
# Three IAM roles: the ECS task execution role (pulls images, resolves
# Secrets Manager values into container env vars, writes CloudWatch Logs —
# used by every one of the 8 task definitions M12 will create), an ECS task
# role (assumed by the running application itself; empty today since no
# service calls an AWS API directly yet, reserved for when one does), and a
# GitHub Actions OIDC deploy role scoped to ECR push only — the natural
# continuation of M10's "design so it can later push to a registry without
# major restructuring" (D59), now that a registry (ECR) actually exists.
#

data "aws_caller_identity" "current" {}

locals {
  name_prefix = "${var.project_name}-${var.environment}"
}

# ── ECS task execution role ─────────────────────────────────────────────────

data "aws_iam_policy_document" "ecs_tasks_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_task_execution" {
  name               = "${local.name_prefix}-ecs-task-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json

  tags = merge(var.tags, { Name = "${local.name_prefix}-ecs-task-execution" })
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution_managed" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "ecs_task_execution_secrets" {
  statement {
    sid       = "ReadPlatformSecrets"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = var.secret_arns
  }
}

resource "aws_iam_role_policy" "ecs_task_execution_secrets" {
  name   = "${local.name_prefix}-ecs-task-execution-secrets"
  role   = aws_iam_role.ecs_task_execution.id
  policy = data.aws_iam_policy_document.ecs_task_execution_secrets.json
}

# ── ECS task role (the running application's own AWS permissions) ──────────
#
# Deliberately empty today — no service currently calls an AWS API directly
# (Postgres/Redis/Kafka access is network-level, via security groups, not
# IAM). Exists now so M12's task definitions have a stable role ARN to
# reference; the same YAGNI discipline as D14/D31/D42 — permissions get added
# here only once a real need exists, not speculatively.

data "aws_iam_policy_document" "ecs_task_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_task" {
  name               = "${local.name_prefix}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume_role.json

  tags = merge(var.tags, { Name = "${local.name_prefix}-ecs-task" })
}

# ── GitHub Actions OIDC deploy role (ECR push only) ─────────────────────────

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # SHA1 fingerprint of the root CA in token.actions.githubusercontent.com's
  # current TLS chain — verified directly against the live endpoint while
  # writing this (`openssl s_client -showcerts`), not copied from a
  # remembered value: GitHub migrated this endpoint's certs to Let's
  # Encrypt/ISRG some time ago, so the DigiCert-chain thumbprint that older
  # tutorials still cite (and that a memorized value here would have
  # silently gotten wrong) is no longer the real chain. AWS has not
  # cryptographically enforced this value for GitHub's provider since 2023
  # (it trusts the connection via its own hardcoded root-CA list instead),
  # but the API still requires a syntactically valid 40-hex-char value here.
  thumbprint_list = ["ab9d0263244dd0326eb67015705a667e79cfe998"]

  tags = merge(var.tags, { Name = "${local.name_prefix}-github-oidc" })
}

locals {
  github_oidc_provider_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : "arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/token.actions.githubusercontent.com"
}

data "aws_iam_policy_document" "github_actions_assume_role" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    # Restricts to this exact repository (any branch/PR/tag) — not the whole
    # GitHub org, and not any other repo that happens to reuse this account's
    # OIDC provider.
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:*"]
    }
  }
}

resource "aws_iam_role" "github_actions_ecr_push" {
  name               = "${local.name_prefix}-github-actions-ecr-push"
  assume_role_policy = data.aws_iam_policy_document.github_actions_assume_role.json

  tags = merge(var.tags, { Name = "${local.name_prefix}-github-actions-ecr-push" })
}

data "aws_iam_policy_document" "github_actions_ecr_push" {
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # this specific action has no resource-level scoping in ECR's IAM model
  }
  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = var.ecr_repository_arns
  }
}

resource "aws_iam_role_policy" "github_actions_ecr_push" {
  name   = "${local.name_prefix}-github-actions-ecr-push"
  role   = aws_iam_role.github_actions_ecr_push.id
  policy = data.aws_iam_policy_document.github_actions_ecr_push.json
}
