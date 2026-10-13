# Third-party notices

## dsh-mobile (Apache-2.0)

Portions of the phone Settings navigation pattern (full-screen list → detail for
Plugins / Scheduled tasks, and the idea of exposing Host plugin-manager /
schedule surfaces to a mobile client) were studied from
[saya-ch/dsh-mobile](https://github.com/saya-ch/dsh-mobile) (Apache-2.0),
Copyright 2026 Guangqi Zhang and contributors.

This project does **not** embed dsh-mobile’s WebView layout or CSS. Pocket RPCs
call DeepSeek Harness Host services in-process; Compose screens are native.
Where code or docs note an adaptation, it refers to Host API shapes and the
list→detail mobile chrome contract, not a verbatim copy of dsh-mobile sources.

License text: https://www.apache.org/licenses/LICENSE-2.0
