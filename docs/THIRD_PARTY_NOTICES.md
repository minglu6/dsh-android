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
