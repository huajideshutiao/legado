package io.legado.app.lib.epublib.domain

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A wrapper class for closing a AndroidZipFile object when the InputStream derived
 * from it is closed.
 * 
 * @author ttopalov
 */
class ResourceInputStream  //private final ZipFile zipFile;
/**
 * Constructor.
 * 
 * @param in The InputStream object.
 */
    (`in`: InputStream?) : FilterInputStream(`in`) {
    @Throws(IOException::class)
    override fun close() {
        super.close()

        //zipFile.close();
    }
}
