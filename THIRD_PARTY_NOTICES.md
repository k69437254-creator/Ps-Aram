# Third-party software and legal notices

## Chiaki-NG

PS Link Mobile is built on the upstream Android implementation and native engine from
[chiaki-ng/chiaki-ng](https://github.com/chiaki-ng/chiaki-ng), pinned to commit
`43728af2e639fecb8a7af1cd68c29820a40de973`. GitHub reports the repository license as
GNU Affero General Public License v3.0 (AGPL-3.0). The complete upstream `COPYING` file
is copied to `LICENSES/AGPL-3.0.txt` by `scripts/prepare_upstream.py` after bootstrap.

The Chiaki-NG source contains additional third-party components and submodules. Their
license notices must be retained from the upstream checkout; do not remove `COPYING`,
`LICENSE`, `NOTICE`, or third-party license files when distributing the combined project.

AGPL-3.0 does permit commercial distribution, but it carries source-availability and
redistribution obligations for covered/modified works. Before selling or distributing
PS Link, review the complete license, the upstream `LicenseRef-AGPL-3.0-only-OpenSSL`
source headers, and the licenses of every included dependency with qualified counsel.
This notice is not legal advice.

Chiaki-NG states that it is not endorsed or certified by Sony Interactive Entertainment LLC.
PS Link is an independent project and is not an official Sony application.

## Google ML Kit

The phone app uses Google ML Kit's bundled Text Recognition SDKs for Latin, Chinese,
Japanese and Korean scripts, and its on-device Translation SDK. Translation language
packs are downloaded on demand by the user and remain on-device for offline translation.
Review Google's applicable SDK terms and attribution guidance before commercial release. Google's current on-device Translation usage guidelines restrict use in embedded-device applications such as TVs without prior written permission. PS Link therefore calls ML Kit Translation only from the Android phone app; the TV companion receives and displays already-translated text and does not invoke the SDK.

## Other dependencies

AndroidX, Material Components, Kotlin, Ktor, OkHttp, Room, Moshi and Oboe are consumed
through their published Maven artifacts. Their licenses and notices are delivered by the
respective artifacts; the final distribution process must include their required notices.
