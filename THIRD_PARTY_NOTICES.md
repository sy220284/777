# Third-party notices

DSH Mobile bundles or derives from the third-party material listed below. Runtime dependencies
resolved by Gradle carry their own licences; this file covers material that ships *inside* this
repository's sources.

## Feather

Fifteen glyphs from the Feather icon set are traced as Compose `ImageVector`s in
`app/src/main/java/com/labteto/dshmobile/ui/components/FeatherIcons.kt`.

- Project: https://feathericons.com
- Source: https://github.com/feathericons/feather
- Licence: MIT

```
MIT License

Copyright (c) 2013-2023 Cole Bemis

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
```


## xterm.js

The terminal renderer bundles `@xterm/xterm` **5.5.0** and `@xterm/addon-fit`
**0.10.0**, downloaded from their npm distribution archives. Runtime code and CSS
are in `app/src/main/assets/terminal/`; their complete MIT license texts are kept
there as `LICENSE.xterm` and `LICENSE.addon-fit`.

- Project: https://github.com/xtermjs/xterm.js
- Sources: https://registry.npmjs.org/@xterm/xterm/-/xterm-5.5.0.tgz
  and https://registry.npmjs.org/@xterm/addon-fit/-/addon-fit-0.10.0.tgz
- No remote scripts or CDN resources are loaded by the terminal screen.


## Bundled Node.js runtime

The Android Harness bundles Node.js 24.18.0 from the official Termux `termux-main`
`nodejs-lts` package. The build verifies Termux's signed package index and each package
SHA-256 before packaging. Node.js core is distributed under the MIT license and includes
additional notices for its bundled dependencies.

The APK also carries the runtime shared libraries required by that Termux Node build.
Their package copyright/license notices are copied from the verified Termux packages into
`assets/runtime/node/notices/` during the build and ship inside the APK.


## Bundled Python runtime

The Android Harness bundles Python 3.14.6 from the official Termux `termux-main`
`python` package. The build verifies Termux's signed package index and every selected
runtime package SHA-256 before packaging.

Python is distributed under the Python Software Foundation License. The APK also carries
the native libraries required by the verified Termux Python dependency closure. Package
copyright and license notices are copied from the verified Termux packages into
`assets/runtime/python/notices/` during the build and ship inside the APK.

The packaged standard library is relocated into the app-private runtime directory. The
Termux-specific default subprocess shell path is rewritten to Android `/system/bin/sh`
so Python `subprocess(..., shell=True)` does not depend on a Termux installation.


## HDiffPatch

The in-app updater bundles the Android patch runtime from HDiffPatch 5.1.3. The build downloads
the published Android SDK archive, verifies its pinned SHA-256, and packages only the ABI required
by the app build.

- Project: https://github.com/sisong/HDiffPatch
- Version: 5.1.3
- Licence: MIT

```
MIT License

HDiffPatch
Copyright (c) 2012-2025 housisong

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
```
