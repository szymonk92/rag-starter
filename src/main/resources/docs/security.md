# Security
Secrets live in Vault; never commit them to Git, and rotate database credentials every 90 days.
Production access requires hardware security keys (YubiKey) and is granted for at most 8 hours through the access portal.
Customer personal data must not be copied to laptops or used in test environments; use the anonymised staging snapshot instead.
Report suspected phishing to security@acme.example within one hour.
