package io.legado.app.lib.epublib.util.commons.io

import java.io.IOException

/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

/**
 * The XmlStreamReaderException is thrown by the XmlStreamReader constructors if
 * the charset encoding can not be determined according to the XML 1.0
 * specification and RFC 3023.
 * 
 * 
 * The exception returns the unconsumed InputStream to allow the application to
 * do an alternate processing with the stream. Note that the original
 * InputStream given to the XmlStreamReader cannot be used as that one has been
 * already read.
 * 
 * 
 * @since 2.0
 */
class XmlStreamReaderException
/**
 * Creates an exception instance if the charset encoding could not be
 * determined.
 * 
 * 
 * Instances of this exception are thrown by the XmlStreamReader.
 * 
 * 
 * @param msg         message describing the reason for the exception.
 * @param contentTypeMime      MIME type in the content-type.
 * @param contentTypeEncoding       encoding in the content-type.
 * @param bomEncoding      BOM encoding.
 * @param xmlGuessEncoding XML guess encoding.
 * @param xmlEncoding      XML prolog encoding.
 */(
    msg: String?,
    /**
     * Returns the MIME type in the content-type used to attempt determining the
     * encoding.
     * 
     * @return the MIME type in the content-type, null if there was not
     * content-type or the encoding detection did not involve HTTP.
     */
    val contentTypeMime: String?,
    /**
     * Returns the encoding in the content-type used to attempt determining the
     * encoding.
     * 
     * @return the encoding in the content-type, null if there was not
     * content-type, no encoding in it or the encoding detection did not
     * involve HTTP.
     */
    val contentTypeEncoding: String?,
    /**
     * Returns the BOM encoding found in the InputStream.
     * 
     * @return the BOM encoding, null if none.
     */
    val bomEncoding: String?,
    /**
     * Returns the encoding guess based on the first bytes of the InputStream.
     * 
     * @return the encoding guess, null if it couldn't be guessed.
     */
    val xmlGuessEncoding: String?,
    /**
     * Returns the encoding found in the XML prolog of the InputStream.
     * 
     * @return the encoding of the XML prolog, null if none.
     */
    val xmlEncoding: String?
) : IOException(msg) {
    /**
     * Creates an exception instance if the charset encoding could not be
     * determined.
     * 
     * 
     * Instances of this exception are thrown by the XmlStreamReader.
     * 
     * 
     * @param msg         message describing the reason for the exception.
     * @param bomEnc      BOM encoding.
     * @param xmlGuessEnc XML guess encoding.
     * @param xmlEnc      XML prolog encoding.
     */
    constructor(
        msg: String?, bomEnc: String?,
        xmlGuessEnc: String?, xmlEnc: String?
    ) : this(msg, null, null, bomEnc, xmlGuessEnc, xmlEnc)

    companion object {
        private const val serialVersionUID = 1L
    }
}
