package com.homefinance.app.core.util

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads and writes a text file the household picked through the Storage Access Framework.
 *
 * The app never chooses a path and never asks for a storage permission: the system picker returns a
 * `content://` URI the app is allowed to use for that one document, and this interface is the only
 * place those bytes are touched. It takes the URI as a `String` so a JVM test can exercise a view
 * model's whole flow with a fake store instead of an emulator.
 *
 * Both methods run on [Dispatchers.IO]: a backup of a large ledger, or a CSV from a cloud provider,
 * must never block the main thread.
 */
interface DocumentStore {

    /** Reads [uri] as UTF-8 text. Throws [IOException] when the document cannot be read. */
    suspend fun readText(uri: String): String

    /** Replaces [uri] with [text] as UTF-8, truncating anything already there. */
    suspend fun writeText(uri: String, text: String)
}

/** The production [DocumentStore], over the application's [ContentResolver]. */
class AndroidDocumentStore(private val resolver: ContentResolver) : DocumentStore {

    override suspend fun readText(uri: String): String = withContext(Dispatchers.IO) {
        resolver.openInputStream(Uri.parse(uri))?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: throw IOException("The selected file could not be opened.")
    }

    override suspend fun writeText(uri: String, text: String) {
        withContext(Dispatchers.IO) {
            // "wt" truncates an existing document; without it, exporting over a longer file would
            // leave the old tail behind the new content.
            val stream = resolver.openOutputStream(Uri.parse(uri), "wt")
                ?: throw IOException("The selected file could not be written.")
            stream.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
    }
}
