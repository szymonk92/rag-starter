# SLOs and error budgets
Every customer-facing service has an availability SLO of 99.9% measured over a rolling 30 days.
Availability is computed over all requests except health checks as $A = \frac{good}{good + bad}$.
The error budget is $$EB = (1 - SLO) \times T$$ where T is the window length; at 99.9% over 30 days that is 43.2 minutes.
When less than 25% of the error budget remains, feature deploys pause and only reliability fixes ship.
![Error budget burn-down: budget remaining in percent over the 30-day window, with the 25% freeze line](img/error-budget-burn.png)
