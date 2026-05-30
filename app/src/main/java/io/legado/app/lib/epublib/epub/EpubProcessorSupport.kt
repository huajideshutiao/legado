package io.legado.app.lib.epublib.epub

import android.util.Log
import io.legado.app.lib.epublib.Constants
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.UnsupportedEncodingException
import java.io.Writer
import java.net.URL
import java.util.Objects
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Various low-level support methods for reading/writing epubs.
 * 
 * @author paul.siegmann
 */
class EpubProcessorSupport {
    internal class EntityResolverImpl : EntityResolver {
        private var previousLocation: String? = null

        @Throws(IOException::class)
        override fun resolveEntity(publicId: String?, systemId: String): InputSource {
            val resourcePath: String?
            if (systemId.startsWith("http:")) {
                val url = URL(systemId)
                resourcePath = "dtd/" + url.getHost() + url.getPath()
                previousLocation = resourcePath
                    .substring(0, resourcePath.lastIndexOf('/'))
            } else {
                resourcePath =
                    previousLocation + systemId.substring(systemId.lastIndexOf('/'))
            }

            if (Objects.requireNonNull<ClassLoader?>(this.javaClass.getClassLoader())
                    .getResource(resourcePath) == null
            ) {
                throw RuntimeException(
                    ("remote resource is not cached : [" + systemId
                        + "] cannot continue")
                )
            }

            val `in` =
                Objects.requireNonNull<ClassLoader?>(EpubProcessorSupport::class.java.getClassLoader())
                    .getResourceAsStream(resourcePath)
            return InputSource(`in`)
        }
    }


    @get:Suppress("unused")
    val documentBuilderFactory: DocumentBuilderFactory
        get() = Companion.documentBuilderFactory!!

    companion object {
        private val TAG: String = EpubProcessorSupport::class.java.getName()

        protected var documentBuilderFactory: DocumentBuilderFactory? = null

        init {
            init()
        }

        private fun init() {
            documentBuilderFactory = DocumentBuilderFactory
                .newInstance()
            documentBuilderFactory!!.setNamespaceAware(true)
            documentBuilderFactory!!.setValidating(false)
        }

        @Throws(UnsupportedEncodingException::class)
        fun createXmlSerializer(out: OutputStream?): XmlSerializer? {
            return Companion.createXmlSerializer(
                OutputStreamWriter(out, Constants.CHARACTER_ENCODING)
            )
        }

        fun createXmlSerializer(out: Writer?): XmlSerializer? {
            var result: XmlSerializer? = null
            try {
                /*
             * Disable XmlPullParserFactory here before it doesn't work when
             * building native image using GraalVM
             */
                val factory: XmlPullParserFactory = XmlPullParserFactory.newInstance()
                factory.setValidating(true)
                result = factory.newSerializer()

                //result = new KXmlSerializer();
                result.setFeature(
                    "http://xmlpull.org/v1/doc/features.html#indent-output", true
                )
                result.setOutput(out)
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "When creating XmlSerializer: " + e.javaClass.getName() + ": " + e
                        .message
                )
            }
            return result
        }

        val entityResolver: EntityResolver
            /**
             * Gets an EntityResolver that loads dtd's and such from the epub4j classpath.
             * In order to enable the loading of relative urls the given EntityResolver contains the previousLocation.
             * Because of a new EntityResolver is created every time this method is called.
             * Fortunately the EntityResolver created uses up very little memory per instance.
             * 
             * @return an EntityResolver that loads dtd's and such from the epub4j classpath.
             */
            get() = EntityResolverImpl()

        /**
         * Creates a DocumentBuilder that looks up dtd's and schema's from epub4j's classpath.
         * 
         * @return a DocumentBuilder that looks up dtd's and schema's from epub4j's classpath.
         */
        fun createDocumentBuilder(): DocumentBuilder? {
            var result: DocumentBuilder? = null
            try {
                result = documentBuilderFactory!!.newDocumentBuilder()
                result.setEntityResolver(entityResolver)
            } catch (e: ParserConfigurationException) {
                Log.e(TAG, e.message!!)
            }
            return result
        }
    }
}
