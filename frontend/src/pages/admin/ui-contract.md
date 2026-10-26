# UI-27 implementation checklist

Route /UI-27 is rendered inside SharedWorkspace; existing shared placeholders and their routes remain owned by SharedWorkspace. Internal dialogs/tabs have no new routes.

Code region: collect every group page and select the first group; collect every managed-code page before enabling full PUT. Left group rail, right editable table, dynamic attrN titles, SYSTEM notice and name/order-only editing. Persisted identities are readonly; BIZ additions use max order + 1. Failed saves retain drafts, show singular field errors and focus the field; success reloads the full group.

Group dialog: create-only ID/name/optional attribute titles with lengths 30/100/50, explicit pending state and refresh after success. Server owns type/order/use flag.

Mapping region/dialog: paginated joined labels preserve inactive names; creation uses shared useCodes for active choices and requires an explicit MAIN/SUB selection without a preselected value. Existing Save/Cancel sends changed allowed fields only; immutable label/target never enter PATCH. Mapping use changes commit through Save without a second confirmation, never physical deletion. All three editable mapping fields display server field errors and support error focus. Code use checkboxes change drafts directly and commit only through full-group Save, without an extra confirmation.

Parameter region/dialog: paginated current rows and all-page applied-version history. Latest stored version supplies value type. Metadata is readonly. Load the complete global close range on each opening, disable save on failure or closed months, render one month input rather than thousands of options. Preserve drafts on error and revalidate exact decimal scale/range.

History region: group filter, default 50, server pagination and readonly table; PARAM label and null actor presentation. No mutation actions.

All regions: loading/empty/error/retry/success, shared always-true permission keys, dirty leave confirmation, beforeunload, cancellation after explicit discard. Shared shell supplies PC-only access, screen tabs, periods and placeholders. ui-style.md is a retrieval-blocker record; verified runner measurements and existing token/components supply the implementation styling, without inventing reference observations.

Code Cancel is an explicit restore action without a leave dialog; it clears touched/server errors and restores the last saved rows. Code Save validates required fields including whitespace-only values and focuses the first invalid input. An entirely closed calendar disables the parameter month input as well as Save.

UI improvement: shared DataTable distinguishes initial loading/error from a successful empty result, preserves existing rows while refreshing, and exposes the persisted dense preference. Pagination shows the server-total range, first/last controls and selected page. Group creation is projected into the shell title action slot; standalone UI27 keeps a local fallback. History reset changes the draft filter only; Query/Enter applies it.
