#
# The Application Load Balancer shell only — internet-facing, public
# subnets, a default fixed-response action on every listener. No target
# group and no forwarding rule exists yet: M12's explicit job is "ECS task
# defs + services, ALB target groups" (per the roadmap), which will replace
# each listener's default_action with a real forward-to-target-group rule
# once gateway-service actually has a running ECS service to point at.
# Matches the Communication Flow (Client -> ALB -> Gateway): only
# gateway-service will ever get a target group here — every other service
# stays internal-only.
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

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "paymentflow: no target group attached yet (M12 wires up gateway-service)."
      status_code  = "503"
    }
  }
}

resource "aws_lb_listener" "https" {
  count = var.certificate_arn != null ? 1 : 0

  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "paymentflow: no target group attached yet (M12 wires up gateway-service)."
      status_code  = "503"
    }
  }
}
