# KuCoin Inverse — Android release signing identity

Package: `com.keytrins.kucoininverse`

Permanent release certificate SHA-256:

`afe0aa73efae83f689f50e641e42b41351fe9e272c252e1b5168fb70d2d31cf4`

Signing policy:

- Every installable production APK must be signed by this certificate.
- Do not publish or use GitHub runner debug signing for production releases.
- The private PKCS12 signing key and credentials are stored outside this public repository in private persistent storage.
- Before distributing an update, verify that its signer certificate SHA-256 exactly matches the fingerprint above.
- Changing or losing this key breaks in-place Android updates for installations signed with it.
