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

The preset mode guides in `PresetGuideLocales.kt` reproduce the English and
Chinese help from `ui-agent-preset` (`guide-locales.ts` and display labels).
`dsh_close.xml` reproduces `IconCloseOutlineRegular` from `ui-primitives`.
These text and vector assets are Copyright (c) 2026 DeepSeek under the MIT
License below.

Chat process presentation and labels follow `ui-chat`. `running_whale_frames.png`
is a lossless 60-frame atlas of its `running-whale@2x.png` (28×28 pixels per
frame, 50 ms each); `ic_running_whale.xml` uses `RunningWhaleTail.REST_PATH`.
Process icons, including `dsh_question.xml`, reproduce `ui-primitives` geometry.
These assets are Copyright (c) 2026 DeepSeek, used under the MIT License:

The file code preview vendors `ui-primitives/src/markdown/highlight.ts`,
`util/code-language/src/index.ts` and `ui-theme/src/styles/shiki.css` in
`code-preview/vendor/`, with the upstream MIT license. Its offline bundle uses
Shiki 4.4.3 and bundled TextMate grammars. The asset generator includes all
bundled npm package license texts in
`app/app/src/main/assets/code-preview/THIRD_PARTY_NOTICES`; these notices and the
DeepSeek license are shipped in the APK alongside the renderer.

The Office warning vector and localized font/conversion feedback follow
`ui-sidebar-documentpreview`. The Excel renderer vendors that module's parsers,
read-only workbook UI, styles, fixtures and required primitives in
`workbook-preview/vendor/`, retaining its MIT license.

The workbook bundle uses FortuneSheet core/react 1.0.4 (MIT, Copyright (c) 2022
Suzhou Ruilisi Technology Co., Ltd), with the official DeepSeek Harness patches;
ExcelJS 4.4.0, SheetJS 0.20.3, fast-xml-parser 5.11.1, fflate 0.8.3,
PapaParse 5.7.0, React/ReactDOM 18.3.1 and clsx 2.1.1. Package licenses and
embedded ExcelJS browser notices ship in
`app/app/src/main/assets/workbook-preview/THIRD_PARTY_NOTICES`.
The npm omissions for `@nodable/entities` and saxes are covered by upstream
[`val-parsers` MIT](https://github.com/nodable/val-parsers/blob/main/LICENSE)
and [`saxes` ISC/MIT](https://github.com/lddubeau/saxes/blob/v5.0.1/LICENSE)
license copies in `workbook-preview/vendor/`.


Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

License text: https://github.com/deepseek-ai/deepseek-harness/blob/master/LICENSE

## AndroidSVG (Apache-2.0)

Plugin metadata SVG data URIs are rendered natively with
[AndroidSVG](https://github.com/BigBadaboom/androidsvg), Copyright 2013 Paul LeBeau.
License text: https://www.apache.org/licenses/LICENSE-2.0
