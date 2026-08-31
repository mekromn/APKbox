# APKbox

APKbox is an Android APK revision vault designed for development workflows where hundreds or thousands of APK builds are almost identical.

Instead of storing every APK in full, APKbox stores APKs as content-defined, SHA-256-addressed chunks. Identical chunks are stored once and every imported build gets a tiny manifest that describes how to reconstruct its exact original bytes. This is resilient to insertions and shifted ZIP offsets and deduplicates across every revision in the vault.

## Core workflow

1. Pick and remember a **Base APK**.
2. Import any number of revisions of that same package.
3. APKbox chunks each APK and compares every chunk against the **entire shared vault**: the base plus every previously imported revision. Only previously unseen chunk data is saved.
4. A chunk introduced by revision A can be reused by revisions B, C, or later even if that chunk never existed in the base APK.
5. Search/select any stored revision.
6. APKbox streams the exact reconstructed APK directly into Android's `PackageInstaller`, verifies its SHA-256 while reconstructing, asks for the required system install approval, and attempts to launch the installed app after success.

No reconstructed APK has to remain on disk after installation.

## Storage design

- Content-defined chunking: 64 KiB minimum / ~256 KiB target / 1 MiB maximum.
- SHA-256 content-addressed chunk files shared by the **whole vault**, not one base-to-revision patch chain.
- Every import may reuse chunks from the base and from any earlier revision simultaneously.
- Exact byte-for-byte APK reconstruction.
- Per-build manifests contain ordered chunk hashes/sizes and APK metadata.
- Deleting an earlier revision cannot break a later one: shared chunks remain while any surviving manifest references them.
- Library storage statistics report logical APK bytes, physical vault bytes, and space saved.
- Revision deletion garbage-collects chunks no longer referenced by any remaining APK.

## Android install constraints

APKbox uses public Android APIs and does not require root. Android still controls package installation:

- The user must grant APKbox permission to install unknown apps.
- The system installer can require confirmation for each install.
- Installing an older version over a newer installed version can be rejected by Android; uninstalling the current app may be required first.
- A build signed by a different key cannot replace an installed copy signed by another key without uninstalling it first.
- APKbox attempts to launch the package after a successful install. Android background-launch policy can occasionally require the user to open it manually.

These restrictions do not affect APK reconstruction or storage savings.

## Project status

The first implementation includes the vault engine, base/revision import, exact reconstruction verification, PackageInstaller integration, storage statistics, search, deletion/garbage collection, and a Material 3 / dynamic-color Compose UI.

Regression tests explicitly verify both base-to-revision reuse and **revision-to-revision reuse of data that never existed in the base**.

A GitHub Actions workflow builds a debug APK on every push to `main`.
