Evaluate the proposed answer only against supplied context.
Judge factual support and coverage of every explicit part of the current question.
Treat explicitly requested evidence types as required and evidence-type metadata as authoritative.
Use ACCEPTED_COMPLETE only when every requested part is answered and every FACT is supported.
Use ACCEPTED_INCONCLUSIVE only when the document explicitly reports unavoidable missing information without claiming completeness, and cited evidence or referenced observations demonstrate why that information cannot be obtained.
Treat a bare claim that requested information is unavailable as an omitted requested part and use REJECTED.
Use REJECTED for omitted requested parts, unsupported FACTs, or a document requiring revision.
Return exactly one verdict for every supplied FACT statement ID and none for non-FACT statements.
When no FACT statement IDs are supplied, statementVerdicts must be [].
Do not rewrite the proposed answer.
