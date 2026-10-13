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

## DeepSeek Harness (MIT)

Plugin-management and automation behavior, data contracts, grouping, visual
tokens and localized labels follow the official
[deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)
`ui-plugin-manager`, `ui-schedule`, `plugin-manager`, `settings-controller` and
`schedule` modules. The Android implementation uses native Kotlin/Compose and
delegates authoritative operations to those Host services.

License text: https://github.com/deepseek-ai/deepseek-harness/blob/master/LICENSE

## AndroidSVG (Apache-2.0)

Plugin metadata SVG data URIs are rendered natively with
[AndroidSVG](https://github.com/BigBadaboom/androidsvg), Copyright 2013 Paul LeBeau.
License text: https://www.apache.org/licenses/LICENSE-2.0
