# OKX Inverse — Android release signing identity

Package: `com.keytrins.okxinverse`

Permanent release certificate SHA-256:

`15747d6c26fcd49b2f10d490a10ca35750c4a8b352d14385fe9c00af7dca4f9f`

Signing policy:

- Every installable production APK must be signed by this certificate.
- Do not publish or use GitHub runner debug signing for production releases.
- The private PKCS12 signing key and credentials are stored outside this public repository in private persistent storage.
- Before distributing an update, verify that its signer certificate SHA-256 exactly matches the fingerprint above.
- Changing or losing this key breaks in-place Android updates for installations signed with it.
