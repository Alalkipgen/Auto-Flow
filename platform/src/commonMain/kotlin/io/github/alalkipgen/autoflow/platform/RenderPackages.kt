package io.github.alalkipgen.autoflow.platform

/** A file made only for a render package, at [path] inside the project folder (`render.json`, `fonts/<file>`). */
class PackageFile(val path: String, val bytes: ByteArray)

/** A written render package: the zip at [zip], its size, and how many of the project's files it holds. */
data class PackedProject(val zip: String, val zipBytes: Long, val files: Int, val bytes: Long)

/**
 * F-7 render package (D-96): one project as a zip with only what its render reads — `project.json`, `source/`, the
 * artifacts the project points at, and [PackageFile]s — no exports, inbox, cache or old versions. It is a backup zip of
 * one project (same manifest, `kind` = `render-package`), so [unpack] (and Settings → Restore on a phone) puts it back
 * safely; another phone, a Windows PC or a Linux server opens it and renders there.
 */
interface RenderPackages {
    /** Writes [projectDir] as `<folder>/…` plus [extras] to [zip] (replaced only when complete). */
    suspend fun pack(projectDir: String, folder: String, extras: List<PackageFile>, zip: String,
        onProgress: (BackupProgress) -> Unit = {}): PlatformResult<PackedProject>

    /** Puts the package's project into [destDir]; an existing folder is never replaced (`Name (restored).autoflow`). */
    suspend fun unpack(zipPath: String, destDir: String): PlatformResult<List<RestoredProject>>
}
