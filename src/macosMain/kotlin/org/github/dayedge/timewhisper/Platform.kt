@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.github.dayedge.timewhisper

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.AppKit.NSSpellChecker
import platform.Foundation.NSMakeRange

/** The macOS system spell checker, asked on the main thread with one document tag for the life of the checker. */
public class NSSpellCheckerFactory : SpellCheckerFactory {
    override fun create(): SpellChecker = object : SpellChecker {
        private var tag: Long? = null
        override suspend fun suggestions(word: String, language: String): List<String> = withContext(Dispatchers.Main) {
            val document = tag ?: NSSpellChecker.uniqueSpellDocumentTag().also { tag = it }
            @Suppress("UNCHECKED_CAST")
            (NSSpellChecker.sharedSpellChecker.guessesForWordRange(NSMakeRange(0u, word.length.toULong()), word, language, document) as? List<String>) ?: emptyList()
        }
        override suspend fun close() = withContext(Dispatchers.Main) {
            tag?.let { NSSpellChecker.sharedSpellChecker.closeSpellDocumentWithTag(it) }; tag = null
        }
    }
}
public actual fun defaultSpellCheckerFactory(): SpellCheckerFactory = NSSpellCheckerFactory()
