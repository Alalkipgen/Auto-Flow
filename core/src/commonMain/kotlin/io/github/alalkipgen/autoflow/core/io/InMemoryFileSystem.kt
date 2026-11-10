package io.github.alalkipgen.autoflow.core.io

/**
 * In-memory [FileSystem] for tests and fakes. [failWhen] injects a failure before an operation runs, e.g. to
 * simulate a crash between "write temp" and "rename". Every completed operation is appended to [log].
 */
class InMemoryFileSystem(
    var failWhen: (op: String, path: String) -> Boolean = { _, _ -> false },
) : FileSystem {
    private val files = linkedMapOf<String, ByteArray>()
    private val dirs = mutableSetOf("/")
    val log = mutableListOf<String>()

    override fun exists(path: String): Boolean = norm(path).let { it in files || it in dirs }

    override fun createDirectories(path: String) {
        check("mkdirs", path)
        var p = norm(path)
        require(p !in files) { "is a file: $p" }
        while (p.isNotEmpty() && dirs.add(p)) p = parent(p)
        log += "mkdirs ${norm(path)}"
    }

    override fun list(path: String): List<String> {
        val dir = norm(path)
        if (dir !in dirs) return emptyList()
        return (files.keys + dirs).filter { it != dir && parent(it) == dir }.map { it.substringAfterLast('/') }.sorted()
    }

    override fun read(path: String): ByteArray {
        check("read", path)
        return files[norm(path)]?.copyOf() ?: throw NoSuchElementException("no file: ${norm(path)}")
    }

    override fun writeDurably(path: String, bytes: ByteArray) {
        check("write", path)
        val p = norm(path)
        require(parent(p) in dirs) { "no parent directory: $p" }
        require(p !in dirs) { "is a directory: $p" }
        files[p] = bytes.copyOf()
        log += "write $p"
    }

    override fun atomicMove(from: String, to: String) {
        check("move", from)
        val src = norm(from)
        val dst = norm(to)
        val bytes = files[src] ?: throw NoSuchElementException("no file: $src")
        require(parent(dst) in dirs) { "no parent directory: $dst" }
        val replaced = dst in files
        files.remove(src)
        files[dst] = bytes
        log += "move $src -> $dst" + if (replaced) " (replace)" else ""
    }

    private fun check(op: String, path: String) {
        if (failWhen(op, norm(path))) throw IllegalStateException("injected failure: $op ${norm(path)}")
    }

    private fun norm(path: String): String {
        val p = "/" + path.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
        return p
    }

    private fun parent(p: String): String = p.substringBeforeLast('/', "").ifEmpty { if (p == "/") "" else "/" }
}
