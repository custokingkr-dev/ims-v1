# Bounded live HTTP/2 framing observations

Four serial, fixed GET health streams reached the current dev frontend Cloud Run ingress over negotiated HTTP/2. Raw HPACK literals preserved duplicate Content-Length and forbidden Transfer-Encoding fields instead of letting a client library reject them locally. Each run had a ten-second whole-network deadline, at most one connection, at most a one-byte submitted body in the actual cases, and bounded response/frame reads. No nested second request, traffic stress, user data or mutating route was used. Seven controlled helper tests passed.

| Case | Actual result |
| --- | --- |
| Normal `/frontend-health` control | HTTP 200 |
| Conflicting Content-Length 0 and 1 | RST_STREAM code 1 (PROTOCOL_ERROR), no response body |
| Content-Length 2 with one DATA byte | RST_STREAM code 2 (INTERNAL_ERROR), no response body; strict conformance check stopped |
| Forbidden Transfer-Encoding: chunked | Separate remaining-case probe: RST_STREAM code 1, no response body |

The third case was rejected, but its error code did not match the strict malformed-message expectation. [RFC 9113 section 8.1.1](https://www.rfc-editor.org/rfc/rfc9113.html#section-8.1.1) requires a PROTOCOL_ERROR for a detected malformed message. The initial helper therefore remains a failed strict-conformance result; it was not regraded into a four-check pass. Only the unexecuted fourth case was subsequently sent. [Initial evidence](acceptance-http2-parser.json), [remaining-case evidence](acceptance-http2-transfer-encoding.json).

These observations cover the existing public Cloud Run/GFE-to-frontend path. They do not identify which intermediary emitted the internal-error reset, prove that a downstream parser never saw the request, certify HTTP/2 end-to-end handling, or certify a future custom-domain/load-balancer path. [Cloud Run's HTTP/2 configuration documentation](https://docs.cloud.google.com/run/docs/configuring/http2) explains the separate end-to-end configuration. Preserve application parsing limits and the existing HTTP/1.1 rejection tests. Recheck all legitimate and malformed controls after a reviewed owned-edge cutover; investigate the differing reset code with Google if strict upstream conformance is required. No application exploit or unauthorized access is established by the observed reset.
