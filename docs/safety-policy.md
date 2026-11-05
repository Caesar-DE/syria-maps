# Safety information policy / سياسة معلومات السلامة

Safety data can hurt people when it is wrong. These rules are enforced in code where possible and
are mandatory for maintainers.

## Rules

1. **Authorized sources only.** Each notice names its source (`source_name`, ideally `source_url`):
   official authorities, recognised humanitarian mine-action organisations, or other sources approved
   by maintainers in a public issue.
2. **Verification before publication.** Only `verification_status = verified` notices with `verified_at`
   are public. Unverified and rejected notices never appear in the API or overlays (enforced by the
   public query and by a database `CHECK` constraint).
3. **Every notice expires.** `expires_at` is required for verified notices. Expired notices disappear
   automatically.
4. **Timestamps shown.** `reported_at`, `valid_from`, `verified_at`, `expires_at` are part of every response.
5. **No inference.** The project never generates hazard predictions, never derives or invents landmine
   or unexploded-ordnance locations, and never interpolates risk areas.
6. **No "safe" labels.** No area or route is ever labelled safe. Every notices response carries the
   disclaimer: *the absence of a notice does not mean an area or route is safe.*
7. **No safe-route guarantees.** The project does not provide routing that claims to avoid danger.
8. **Corrections.** Wrong notices are set to `rejected` immediately; incidents are documented publicly.

## Current state

No authorized safety or traffic source is connected. The `traffic` and `safety-notices` layers are
`experimental` and return empty results in production.

## عربي (ملخص)

لا تُنشر إلا إشعارات من جهات معتمدة، موثقة، مع المصدر وتاريخ التحقق وتاريخ انتهاء إلزامي.
لا يولّد المشروع أي توقعات مخاطر أو مواقع ألغام، ولا يصف أي منطقة أو طريق بأنه «آمن»،
ولا يقدم ضمان مسارات آمنة. **عدم وجود تحذير لا يعني أن المكان آمن.**
