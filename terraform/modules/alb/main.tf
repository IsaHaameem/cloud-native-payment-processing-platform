#
# The Application Load Balancer, plus its one real target: gateway-service
# (D66/M12 — every other service stays internal-only, matching the
# Communication Flow: Client -> ALB -> Gateway). M11 shipped this module with
# only a fixed-response default action and no target group, deliberately
# deferred to M12's explicit roadmap scope ("ECS task defs + services, ALB
# target groups") — this is that deferred work being completed, not a
# redesign of M11's decision.
#
# target_type = "ip" (not "instance") because Fargate awsvpc-mode tasks are
# addressed by ENI IP, not EC2 instance ID — there is no EC2 instance to
# register.
#

locals {
  name_prefix = "${var.project_name}-${var.environment}"
}

resource "aws_lb" "this" {
  name               = "${local.name_prefix}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.security_group_id]
  subnets            = var.public_subnet_ids

  tags = merge(var.tags, { Name = "${local.name_prefix}-alb" })
}

resource "aws_lb_target_group" "gateway" {
  name        = "${local.name_prefix}-gateway-tg"
  port        = var.gateway_container_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    path                = var.gateway_health_check_path
    protocol            = "HTTP"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
    timeout             = 5
    matcher             = "200"
  }

  tags = merge(var.tags, { Name = "${local.name_prefix}-gateway-tg" })
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway.arn
  }
}

# ── agentic-commerce-service ingress (AD-8 detachable, NOT gateway-routed) ──
#
# The Developer Portal's server-side proxy calls `${AGENTIC_SERVICE_URL}/api/agentic/*`
# (developer-portal/src/lib/agentic/client.ts). Agentic is deliberately not behind the
# gateway (AD-8) and stays private on 8095 — this is a path-based listener rule that
# carves `/api/agentic/*` off the same ALB and forwards it to agentic's own target
# group, leaving every other path on the default action (the gateway). Auth is
# unchanged: agentic's InternalContextFilter rejects an unsigned/stale/forged HMAC
# context with 401 before any controller runs, exactly as for `/internal/v1/**`.
#
# Opt-in via var.enable_agentic_ingress so the module stays a no-op for any caller
# that does not set it.

resource "aws_lb_target_group" "agentic" {
  count = var.enable_agentic_ingress ? 1 : 0

  name        = "${local.name_prefix}-agentic-tg"
  port        = var.agentic_container_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    path              = var.agentic_health_check_path
    protocol          = "HTTP"
    healthy_threshold = 2
    # Generous: agentic-commerce-service cold-starts in ~175s on 0.25 vCPU. The ECS
    # health-check grace period (modules/ecs-service, 180s) covers the boot; these
    # thresholds add 5 x 30s of tolerance on top so a slow-but-healthy task is not
    # cycled the moment the grace period ends.
    unhealthy_threshold = 5
    interval            = 30
    timeout             = 10
    matcher             = "200"
  }

  tags = merge(var.tags, { Name = "${local.name_prefix}-agentic-tg" })
}

resource "aws_lb_listener_rule" "agentic_http" {
  count = var.enable_agentic_ingress ? 1 : 0

  listener_arn = aws_lb_listener.http.arn
  priority     = var.agentic_listener_rule_priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.agentic[0].arn
  }

  condition {
    path_pattern {
      values = var.agentic_path_patterns
    }
  }

  tags = merge(var.tags, { Name = "${local.name_prefix}-agentic-rule" })
}

# Same rule on the HTTPS listener, so it keeps working the moment a certificate is
# supplied (var.certificate_arn) and the HTTPS listener is created.
resource "aws_lb_listener_rule" "agentic_https" {
  count = var.enable_agentic_ingress && length(aws_lb_listener.https) > 0 ? 1 : 0

  listener_arn = aws_lb_listener.https[0].arn
  priority     = var.agentic_listener_rule_priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.agentic[0].arn
  }

  condition {
    path_pattern {
      values = var.agentic_path_patterns
    }
  }

  tags = merge(var.tags, { Name = "${local.name_prefix}-agentic-rule-https" })
}

resource "aws_lb_listener" "https" {
  count = var.certificate_arn != null ? 1 : 0

  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway.arn
  }
}
