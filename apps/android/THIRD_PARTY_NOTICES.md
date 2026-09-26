# Third-party notices

DSH Links App's APK distributes the runtime components and font listed below.
Their copyright and license terms remain with their respective authors. The
exact versions used by this build are recorded in `gradle/libs.versions.toml`
and `app/build.gradle.kts`.

| Component | License | Project |
| --- | --- | --- |
| AndroidX Core, AppCompat, Activity Compose, Compose UI, Material3 | Apache-2.0 | https://developer.android.com/jetpack |
| Kotlin | Apache-2.0 | https://kotlinlang.org/ |
| Material Components for Android | Apache-2.0 | https://github.com/material-components/material-components-android |
| ZXing Android Embedded | Apache-2.0 | https://github.com/journeyapps/zxing-android-embedded |
| Coil | Apache-2.0 | https://github.com/coil-kt/coil |
| OkHttp | Apache-2.0 | https://square.github.io/okhttp/ |
| KaTeX 0.18.4 (bundled in APK assets, rendered via WebView) | MIT | https://github.com/KaTeX/KaTeX |
| KaTeX fonts (bundled woff2) | SIL Open Font License 1.1 | https://github.com/KaTeX/KaTeX/tree/master/fonts |
| Mermaid 11.17.2 (bundled in APK assets, rendered via WebView) | MIT | https://github.com/mermaid-js/mermaid |
| Plus Jakarta Sans (bundled TTF font) | SIL Open Font License 1.1 | https://github.com/tokotype/PlusJakartaSans |

## KaTeX notice

KaTeX
Copyright (c) 2013-2020 Khan Academy and other contributors
https://github.com/KaTeX/KaTeX

Licensed under the MIT License. Permission is hereby granted, free of charge,
to any person obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, subject to the
above copyright notice and this permission notice being included in all
copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY.

## KaTeX fonts notice

The KaTeX fonts bundled from the KaTeX distribution are licensed under the
SIL Open Font License, Version 1.1. See https://openfontlicense.org

## Mermaid notice

Mermaid
Copyright (c) 2014-2024 Knut Sveidqvist and mermaid-js contributors
https://github.com/mermaid-js/mermaid

Licensed under the MIT License. Permission is hereby granted, free of charge,
to any person obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, subject to the
above copyright notice and this permission notice being included in all
copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY.

## Plus Jakarta Sans notice

Plus Jakarta Sans
Copyright 2020 The Plus Jakarta Sans Project Authors
https://github.com/tokotype/PlusJakartaSans

This Font Software is licensed under the SIL Open Font License, Version 1.1.
See https://openfontlicense.org

## Build-only dependencies

The following dependencies are used for local tests or debug tooling and are
not packaged in the release APK:

- JUnit 4 (`testImplementation`)
- JSON-java (`testImplementation`, JVM implementation for `org.json` tests)
- Compose UI tooling (`debugImplementation`)

Where a dependency includes its own license or notice text, that text is
included in the dependency artifact and remains applicable. This file is the
app's distribution index; it is not a replacement for those upstream terms.
