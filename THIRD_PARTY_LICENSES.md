# Third-party licenses

Curveon uses the following open-source components.

---

## BRouter 1.7.10 (routing engine)

`routing/libs/brouter-1.7.10-ro.jar`  
<https://github.com/abrensch/brouter>

MIT License

Copyright (c) 2012–2024 Arndt Brenschede and contributors

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

---

## MapLibre Android SDK

<https://github.com/maplibre/maplibre-gl-native>

BSD 2-Clause License

Copyright (c) 2021 MapLibre contributors  
Copyright (c) 2014–2020 Mapbox

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

---

## Noto Sans (map glyphs)

`app/src/main/assets/map/fonts/`  
<https://fonts.google.com/noto>

Licensed under the Apache License, Version 2.0.  
Copyright (c) Google LLC.

<https://www.apache.org/licenses/LICENSE-2.0>

---

## Android Jetpack (including Compose, Room and CameraX), Kotlin and kotlinx.coroutines, OkHttp, Coil, Tink, ZXing

All fetched as Maven/Gradle dependencies at build time; not vendored in this repository.  
Each is licensed under the Apache License, Version 2.0.

<https://www.apache.org/licenses/LICENSE-2.0>

---

## Web planner page: MapLibre GL JS 5.6.0, PMTiles 4.3.0, qrcode-generator 1.4.4

Served with the web planner (relay.curveon.no/plan), not part of the apps. Fetched at pinned versions by
`tools/webplanner_assemble.sh`; not vendored in this repository.

- **MapLibre GL JS**, BSD 3-Clause. <https://github.com/maplibre/maplibre-gl-js/blob/main/LICENSE.txt>
- **PMTiles** (JavaScript), BSD 3-Clause. <https://github.com/protomaps/PMTiles/blob/main/LICENSE>
- **qrcode-generator** by Kazuhiko Arase, MIT. <https://github.com/kazuhikoarase/qrcode-generator/blob/master/LICENSE>

---

## Map data

The offline and online map tiles are built from **OpenStreetMap** data.

© OpenStreetMap contributors — <https://www.openstreetmap.org/copyright>  
Map data is available under the Open Database Licence (ODbL).  
<https://opendatacommons.org/licenses/odbl/>

The Protomaps tile format and base layers are used under the BSD 3-Clause license.  
<https://github.com/protomaps/protomaps.js>

Speed limit data sources by country:

- Norway: Statens vegvesen NVDB — Norwegian government open data (NLOD)
- Finland: Digiroad — Finnish Transport Agency open data (CC BY 4.0)
- Netherlands: NDW/RWS — Dutch government open data (CC BY 4.0)
- Flanders: AWV — Flemish government open data (Modellicentie Gratis Hergebruik)

Business/POI data for offline search includes data from **Overture Maps Foundation** under the
Community Data License Agreement – Permissive, Version 2.0 (CDLA-Permissive-2.0).  
<https://overturemaps.org>
