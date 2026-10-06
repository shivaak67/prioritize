# Dashboard redesign: focus-first Concept A

## Outcome

The dashboard now leads with a single, actionable **Next up** priority instead of giving every section equal visual weight. The redesign keeps the existing Prioritize data model and APIs, but organizes the same task, Canvas, calendar, focus, and assistant capabilities into a faster daily decision flow.

The implementation is limited to the dashboard component on branch `feature/dashboad-redesign`:

- `frontend/src/app/features/dashboard/dashboard.component.ts`
- `frontend/src/app/features/dashboard/dashboard.component.html`
- `frontend/src/app/features/dashboard/dashboard.component.scss`
- `frontend/src/app/features/dashboard/dashboard.component.spec.ts`

No backend, database, API-contract, Focus-page, or Assistant-page changes were required.

## What users see

The page is organized into four levels of information:

1. **Work overview:** compact Due today, Overdue, and This week cards plus a weekly completion strip.
2. **Next up:** the highest-priority open item, its due state and time, actions, a five-item pager, and contextual AI shortcuts.
3. **Today and this week:** a chronological agenda and a Monday-through-Sunday workload strip.
4. **Upcoming deadlines:** overdue work, open assignments due during the next seven days, and upcoming calendar events.

The existing Canvas setup and onboarding flows remain available. Loading skeletons, useful empty states, and explicit error states replace ambiguous blank panels.

## How “Next up” is chosen

“Next up” considers open personal tasks and open Canvas deadlines that have a due date. Ordinary calendar events remain in the agenda and deadline views but do not become focus priorities.

Items are ordered by:

1. Urgency: overdue, then due today, then later.
2. Priority: Urgent, High, Medium, then Low.
3. Due date and time.

The first five results are available through the previous and next buttons. Completing the current result resets the pager and promotes the next qualifying item.

Date-only items are described deliberately: personal tasks use **Any time**, while Canvas assignments use **Time not provided** because Canvas did not supply a deadline time.

## Actions and destinations

- **Start focus session** is available for personal tasks and opens `/focus?task=<id>`, where the linked task is preselected.
- **Open assignment** is shown for a Canvas deadline with a Canvas URL. When no URL is available, the action opens Calendar instead.
- **Mark complete** calls the existing API and updates the dashboard only after the server confirms success. Personal tasks use the task-update endpoint; Canvas deadlines use Prioritize’s Canvas-completion endpoint.
- Canvas completion is clearly described as local to Prioritize. It does not submit coursework or change the assignment in Canvas.
- The header AI form, inline AI form, and suggestion chips open `/assistant?q=<question>`.
- Metric cards, agenda entries, deadline entries, View all, and View calendar link to the existing filtered Tasks or Calendar views.
- A failed completion leaves the item in place and shows a retryable action error.

## Data semantics

The redesign intentionally retains the previous client-side meanings:

- **Due today, Overdue, and This week** count open personal tasks plus open Canvas deadlines.
- **Weekly progress** includes personal tasks and Canvas assignments due in the current Monday-through-Sunday week; cancelled tasks are excluded.
- **Today** combines open personal tasks due today, open Canvas deadlines due today, and ordinary calendar events that overlap today.
- **Upcoming deadlines** includes all older overdue work, open work due before the seven-day horizon, and calendar events (including Canvas events) that have not ended.
- **This week** displays open-work counts for each day of the current Monday-through-Sunday week.

The redesign does not wire in a dashboard-summary endpoint. It continues deriving these values from the existing task and event responses so the established due-this-week semantics remain unchanged.

Tasks and events still load together through the existing full-list APIs. If either request fails, the dashboard shows one error with **Try again** and hides metrics and content panels. This all-or-nothing behavior prevents a partial response from incorrectly claiming that the user is caught up.

## Responsive and accessibility behavior

- The two main columns stack below 1180 px.
- The greeting and AI search stack below 800 px.
- Below 620 px, Due today and Overdue share a row while This week and Weekly progress each span a full row. Actions become single-column, the week strip scrolls horizontally, agenda rows become compact, and the Canvas action occupies its own row.
- The verified 1440, 1024, 768, and 390 px viewports have no horizontal page overflow.
- AI fields have visible or screen-reader labels; pager and submit buttons have explicit accessible names.
- Sections, ordered lists, time values, loading labels, and status text use semantic markup.
- Metric screen-reader labels announce loading instead of reading a temporary zero.
- All controls use native keyboard behavior, status is not communicated by color alone, and reduced-motion preferences are respected.

## Verification

Automated checks:

- Production Angular build and legal-page generation: passed with Node 22.
- Focused dashboard Karma suite in ChromeHeadless: 10 of 10 tests passed.
- Tests cover deadline ranges, current-day time handling, Next up ordering, Canvas completion, weekly progress, Canvas connection state, and Canvas deadline semantics.

Browser verification against the real Spring API with a temporary H2 database confirmed:

- Completing an overdue task persists after reload, changes Overdue from 1 to 0, and advances Next up.
- Start focus session opens Focus with the correct task selected.
- An AI suggestion opens Assistant with the expected `q` query parameter.
- The dashboard renders without Vite overlays, browser JavaScript errors, or horizontal overflow at 1440, 1024, 768, and 390 px.
- Blocking the Tasks request shows the error/retry state without false zero or “caught up” content; removing the block and selecting Try again restores the dashboard.
- Blocking a task-completion request leaves the task and counts unchanged and shows an actionable error.

The local browser preview used temporary in-memory data, not a real user account or live Canvas data. That data resets whenever the backend restarts.

### Local preview commands used

Frontend:

```powershell
npx --yes --package=node@22 node node_modules/@angular/cli/bin/ng.js serve --host localhost --port 4200 --prebundle=false
```

The `--prebundle=false` option is required in this environment because Vite prebundling otherwise crashes even under Node 22.

Backend:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.useTestClasspath=true' '-Dspring-boot.run.profiles=test'
```

This starts the real Spring API with the test profile and in-memory H2 storage.

## Current limitations

- A Canvas deadline cannot start a tracked Focus session because Focus records time against personal task IDs. The dashboard opens the Canvas assignment or Calendar instead of inventing an unsupported relationship.
- Marking a Canvas item complete only changes Prioritize’s local completion flag; it does not submit or complete work in Canvas.
- The dashboard still downloads complete task and event lists and derives its views in the browser. Summary-endpoint wiring, a dedicated dashboard/BFF response, and independent partial loading remain future improvements.
- Browser verification used controlled preview data. Live-account and live-Canvas integration behavior was not exercised in this pass.
