# MSG91 Production Setup

This project uses the notification service as the single integration boundary for OTP, SMS, email, and WhatsApp. Schools should not call MSG91 directly.

## Sender Model

- Email: use the shared Custoking domain with school-specific aliases such as `delhipublicschool@custoking.com`.
- WhatsApp default: send through the Custoking-managed MSG91 WhatsApp sender with templates carrying the school display name.
- WhatsApp advanced: allow a school to connect its own WhatsApp Business account later, then store the school sender mapping in the notification service and route messages by `schoolId`.

## Required MSG91 Console Setup

1. Verify the `custoking.com` email domain in MSG91.
2. Add DNS records from MSG91 in GoDaddy for SPF, DKIM, and return-path/tracking if enabled.
3. Create approved WhatsApp templates for OTP, reports, fee reminders, attendance alerts, and result notifications.
4. Keep `MSG91_DRY_RUN=true` until template approval and sender verification are complete.
5. Store the MSG91 auth key only in Secret Manager as `msg91-auth-key`.

## Backend Configuration

Required secrets:

- `msg91-auth-key`
- `notification-service-token`

Required Cloud Run environment:

- `MSG91_DRY_RUN=false` only after domain and WhatsApp template approval.
- `IMS_NOTIFICATION_SERVICE_HYBRID_SENDER_EMAIL_DOMAIN=custoking.com`
- `IMS_NOTIFICATION_SERVICE_HYBRID_SENDER_WHATSAPP_LANGUAGE_CODE=en`

## School Onboarding Flow

1. Create school in IMS.
2. Allocate email alias: `<school-short-code>@custoking.com`.
3. Assign WhatsApp sender mode:
   - `CUSTOKING_SHARED` for launch.
   - `SCHOOL_OWNED` after school connects its WhatsApp Business account.
4. Store sender preferences against `schoolId`.
5. Send test notification in dry-run first, then live.

## Go-Live Checks

- Secret exists and has an enabled version.
- Notification service health is UP.
- Dry-run disabled only for production-approved sender/template pairs.
- At least one test email and one test WhatsApp template message succeeds for a pilot school.
