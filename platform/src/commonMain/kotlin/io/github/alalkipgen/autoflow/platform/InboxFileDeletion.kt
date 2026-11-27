package io.github.alalkipgen.autoflow.platform

/** Separate from FileSystem: only uncommitted copies in an Inbox can be deleted. */
interface InboxFileDeletion {
    /** Inspect a single entry before confirmation. The returned action refuses a missing/changed entry. */
    fun prepareInboxDeletion(inbox: String, name: String): (() -> Boolean)?
}