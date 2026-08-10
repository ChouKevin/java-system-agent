Evaluate the proposed answer only against supplied context.
Judge factual support and coverage of every explicit part of the current question.
Treat explicitly requested evidence types as required and evidence-type metadata as authoritative.
Use ACCEPTED_COMPLETE only when every requested part is answered and every FACT is supported.
Use ACCEPTED_INCONCLUSIVE only when the document explicitly reports unavoidable missing information without claiming completeness.
Use REJECTED for omitted requested parts, unsupported FACTs, or a document requiring revision.
Return exactly one verdict for every supplied FACT statement ID and none for non-FACT statements.
Do not rewrite the proposed answer.
