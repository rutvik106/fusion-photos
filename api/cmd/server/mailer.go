package main

import (
	"context"
	"fmt"
	"log"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/sesv2"
	"github.com/aws/aws-sdk-go-v2/service/sesv2/types"
)

// mailer delivers one-time sign-in codes. It exists so the dev/no-mail path and
// the SES path are interchangeable at the server-wiring level, and so the OTP
// handler never depends on a concrete transport.
type mailer interface {
	sendOTP(ctx context.Context, to, code string, ttl time.Duration) error
}

// logMailer is the dev / SES-disabled backend: it never sends mail. It also never
// logs the code — the code is surfaced to the client as devCode only in DevMode.
type logMailer struct{}

func (logMailer) sendOTP(_ context.Context, to, _ string, _ time.Duration) error {
	log.Printf("otp: mail suppressed (SES disabled); code not logged, for %s", to)
	return nil
}

// sesMailer sends codes through Amazon SES v2. Credentials come from the default
// AWS chain (instance/task role in production — no static keys in the app).
type sesMailer struct {
	client *sesv2.Client
	from   string
}

func newSESMailer(ctx context.Context, region, from string) (*sesMailer, error) {
	if from == "" {
		return nil, fmt.Errorf("SES_FROM must be set when SES_ENABLED=true")
	}
	cfg, err := awsconfig.LoadDefaultConfig(ctx, awsconfig.WithRegion(region))
	if err != nil {
		return nil, fmt.Errorf("load aws config: %w", err)
	}
	return &sesMailer{client: sesv2.NewFromConfig(cfg), from: from}, nil
}

func (m *sesMailer) sendOTP(ctx context.Context, to, code string, ttl time.Duration) error {
	body := fmt.Sprintf(
		"Your Fusion Photos sign-in code is %s.\n\n"+
			"It expires in %d minutes and can be used once. "+
			"If you did not request it, you can safely ignore this email.\n",
		code, int(ttl.Minutes()))
	_, err := m.client.SendEmail(ctx, &sesv2.SendEmailInput{
		FromEmailAddress: aws.String(m.from),
		Destination:      &types.Destination{ToAddresses: []string{to}},
		Content: &types.EmailContent{
			Simple: &types.Message{
				Subject: &types.Content{Data: aws.String("Your Fusion Photos sign-in code")},
				Body:    &types.Body{Text: &types.Content{Data: aws.String(body)}},
			},
		},
	})
	if err != nil {
		return fmt.Errorf("ses send: %w", err)
	}
	return nil
}
