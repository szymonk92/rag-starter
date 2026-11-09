# Deployments
All services deploy through the GitHub Actions pipeline; manual kubectl apply in production is forbidden.
Production deploys are blocked on Fridays after 15:00 and during the December freeze (15 December to 2 January).
Every deploy uses a canary: 5% of traffic for 20 minutes, then 50%, then 100%, with automatic rollback if the error rate exceeds 1%.
Database migrations must be backward compatible for one release (expand, migrate, contract).
