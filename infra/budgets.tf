# Cost guardrails. AWS Budgets and Cost Anomaly Detection are DELAYED signals, not hard
# spending ceilings — the application-level quotas and rate limits are still required.

resource "aws_budgets_budget" "monthly" {
  name         = "${var.name_prefix}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.budget_amount_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  dynamic "notification" {
    for_each = length(var.budget_alert_emails) > 0 ? [1] : []
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = 80
      threshold_type             = "PERCENTAGE"
      notification_type          = "FORECASTED"
      subscriber_email_addresses = var.budget_alert_emails
    }
  }
}

resource "aws_ce_anomaly_monitor" "cost" {
  name              = "${var.name_prefix}-cost-anomaly"
  monitor_type      = "DIMENSIONAL"
  monitor_dimension = "SERVICE"
}

resource "aws_ce_anomaly_subscription" "cost" {
  name             = "${var.name_prefix}-cost-anomaly"
  frequency        = "DAILY"
  monitor_arn_list = [aws_ce_anomaly_monitor.cost.arn]

  subscriber {
    type    = "EMAIL"
    address = length(var.budget_alert_emails) > 0 ? var.budget_alert_emails[0] : "ops@example.com"
  }

  threshold_expression {
    dimension {
      key           = "ANOMALY_TOTAL_IMPACT_ABSOLUTE"
      match_options = ["GREATER_THAN_OR_EQUAL"]
      values        = ["100"]
    }
  }
}
