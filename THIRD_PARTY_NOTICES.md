# Third-party software and research

The MIT license in the repository root covers original RoomScan code. Third-party
code, SDKs, assets and runtime components retain their own licenses. No endorsement
by the upstream authors is implied.

| Component | Role | Version | License / upstream |
| --- | --- | --- | --- |
| [Brush](https://github.com/ArthurBrussee/brush) | Gaussian training; separate executable | 0.3.0 | Apache-2.0; `licenses/brush/` |
| [COLMAP / PyCOLMAP](https://github.com/colmap/colmap) | Optional, installed from official PyPI on first launch | 4.2.1 | BSD-3-Clause with separately licensed dependencies, including GPL SuiteSparse modules |
| [Spark](https://github.com/sparkjsdev/spark) | Offline Gaussian rendering | 2.4.0 | MIT; `licenses/viewer/SPARK-LICENSE.txt` |
| [Three.js](https://github.com/mrdoob/three.js) | WebGL scene and controls | 0.180.0 | MIT; `licenses/viewer/THREE-LICENSE.txt` |
| [FastAPI](https://github.com/fastapi/fastapi), [Uvicorn](https://github.com/encode/uvicorn) | Local API | requirements.lock.txt | MIT / BSD-3-Clause; Python inventory |
| [Zeroconf](https://github.com/python-zeroconf/python-zeroconf) | LAN discovery | requirements.lock.txt | LGPL-2.1-or-later; Python inventory and bundled license text |
| [Cryptography](https://github.com/pyca/cryptography) | TLS identity | requirements.lock.txt | Apache-2.0 / BSD-3-Clause; bundled dependency notices |
| [ZXing](https://github.com/zxing/zxing) | QR scanning | 3.5.3 | Apache-2.0 |
| [AndroidX Media3](https://github.com/androidx/media) | Video playback | 1.8.0 | Apache-2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin) | Android language runtime | 1.9.24 | Apache-2.0 |
| [ARCore SDK](https://developers.google.com/ar) | Optional AR capture | 1.57.0 | Google SDK terms; not covered by RoomScan's MIT license |
| CPython / Tcl / Tk | Portable Windows runtime and UI | Build inventory | PSF / Tcl/Tk terms, in `licenses/python/interpreter/` |
| [PyInstaller](https://pyinstaller.org/en/stable/license.html) | Packaging tool / bootloader | requirements-desktop.txt | GPL with distribution exception; collected license text |

`licenses/python/inventory.json` records runtime and bootloader distributions,
versions, upstream URLs and license files. Development-only tools and the separately
installed PyCOLMAP wheel are excluded. The Windows bundle contains the dependency modules selected by PyInstaller.
`licenses/android/` contains the resolved Android runtime coordinates and available
embedded notices. Viewer licenses also remain beside vendored JS inside the APK.

The Windows ZIP distributes Brush unchanged with its upstream README and license.
Upstream release: https://github.com/ArthurBrussee/brush/releases/tag/v0.3.0.
LGPL components are distributed as separate runtime files in `_internal`; recipients
may replace those components with compatible versions and debug those modifications.
Upstream source locations are recorded in the inventory. RoomScan's source and build
scripts allow rebuilding the complete application with modified dependencies.

The Windows ZIP does **not** redistribute the PyCOLMAP wheel or its native DLLs.
Its first-run installer fetches the unmodified upstream CPython 3.12 x64 wheel
directly from PyPI and verifies the pinned SHA-256 in `assets/pycolmap-wheel.json`.
The recipient's installation resides in `.tools/pycolmap/` and includes upstream
metadata. SuiteSparse SPQR is GPL-2.0-or-later; CHOLMOD includes separately licensed
modules. See [SuiteSparse licensing](https://github.com/DrTimothyAldenDavis/SuiteSparse/blob/dev/LICENSE.txt)
and the [COLMAP wheel build](https://github.com/colmap/colmap/blob/4.2.1/.github/workflows/build-pycolmap.yml).
The repository MIT license does not relicense these components. Redistributing an
installed engine requires complying with those components' licenses and providing
their corresponding sources as required.

This app includes ARCore functionality provided by Google. Use of ARCore functionality
is subject to the current [Google Terms of Service](https://policies.google.com/terms)
and [Google Privacy Policy](https://policies.google.com/privacy), with the
[ARCore Additional Terms](https://developers.google.com/ar/develop/terms).
The Google Play Services for AR APK is not redistributed in this project.

## Research attribution

- Kerbl, B., Kopanas, G., Leimkühler, T., and Drettakis, G. (2023).
  *3D Gaussian Splatting for Real-Time Radiance Field Rendering*.
  [Project and paper](https://repo-sam.inria.fr/fungraph/3d-gaussian-splatting/).
- Schönberger, J. L., and Frahm, J.-M. (2016). *Structure-from-Motion Revisited*.
  [COLMAP publications](https://colmap.github.io/).

RoomScan integrates Brush, COLMAP and Spark. These references acknowledge the
underlying work; RoomScan does not claim authorship of their algorithms. The
original Graphdeco Gaussian Splatting implementation is not vendored here.
