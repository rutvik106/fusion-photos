# Runtime secrets for the API. The DB master password is managed by RDS itself
# (manage_master_user_password); this secret holds the other deployment knobs.

resource "random_password" "otp_pepper" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "app" {
  name_prefix = "${var.name_prefix}/app/"
  description = "Fusion Photos API runtime config (OTP pepper, SES sender)."
  tags        = local.tags
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id
  secret_string = jsonencode({
    OTP_PEPPER = random_password.otp_pepper.result
    SES_FROM   = "no-reply@example.com"
  })
}
