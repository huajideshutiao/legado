package io.legado.app.lib.epublib.epub

import android.util.Log
import io.legado.app.lib.epublib.domain.EpubBook

/**
 * A book processor that combines several other bookprocessors
 * 
 * 
 * Fixes coverpage/coverimage.
 * Cleans up the XHTML.
 * 
 * @author paul.siegmann
 */
class BookProcessorPipeline @JvmOverloads constructor(var bookProcessors: MutableList<BookProcessor>? = null) :
    BookProcessor {
    override fun processBook(book: EpubBook?): EpubBook? {
        var book: EpubBook? = book
        if (bookProcessors == null) {
            return book
        }
        for (bookProcessor in bookProcessors) {
            try {
                book = bookProcessor.processBook(book)
            } catch (e: Exception) {
                Log.e(TAG, e.message, e)
            }
        }
        return book
    }

    fun addBookProcessor(bookProcessor: BookProcessor?) {
        if (this.bookProcessors == null) {
            bookProcessors = ArrayList<BookProcessor>()
        }
        this.bookProcessors!!.add(bookProcessor!!)
    }

    fun addBookProcessors(bookProcessors: MutableCollection<BookProcessor?>) {
        if (this.bookProcessors == null) {
            this.bookProcessors = ArrayList<BookProcessor>()
        }
        this.bookProcessors!!.addAll(bookProcessors.filterNotNull())
    }


    fun setBookProcessingPipeline(
        bookProcessingPipeline: MutableList<BookProcessor>?
    ) {
        this.bookProcessors = bookProcessingPipeline
    }

    companion object {
        private val TAG: String = BookProcessorPipeline::class.java.getName()
    }
}
