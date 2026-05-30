package io.legado.app.lib.epublib.util.zip

internal interface ZipConstants {
    companion object {
        /* The local file header */
        const val LOCHDR: Int = 30
        val LOCSIG: Int = 'P'.code or ('K'.code shl 8) or (3 shl 16) or (4 shl 24)

        const val LOCVER: Int = 4
        const val LOCFLG: Int = 6
        const val LOCHOW: Int = 8
        const val LOCTIM: Int = 10
        const val LOCCRC: Int = 14
        const val LOCSIZ: Int = 18
        const val LOCLEN: Int = 22
        const val LOCNAM: Int = 26
        const val LOCEXT: Int = 28

        /* The Data descriptor */
        val EXTSIG: Int = 'P'.code or ('K'.code shl 8) or (7 shl 16) or (8 shl 24)
        const val EXTHDR: Int = 16

        const val EXTCRC: Int = 4
        const val EXTSIZ: Int = 8
        const val EXTLEN: Int = 12

        /* The central directory file header */
        val CENSIG: Int = 'P'.code or ('K'.code shl 8) or (1 shl 16) or (2 shl 24)
        const val CENHDR: Int = 46

        const val CENVEM: Int = 4
        const val CENVER: Int = 6
        const val CENFLG: Int = 8
        const val CENHOW: Int = 10
        const val CENTIM: Int = 12
        const val CENCRC: Int = 16
        const val CENSIZ: Int = 20
        const val CENLEN: Int = 24
        const val CENNAM: Int = 28
        const val CENEXT: Int = 30
        const val CENCOM: Int = 32
        const val CENDSK: Int = 34
        const val CENATT: Int = 36
        const val CENATX: Int = 38
        const val CENOFF: Int = 42

        /* The entries in the end of central directory */
        val ENDSIG: Int = 'P'.code or ('K'.code shl 8) or (5 shl 16) or (6 shl 24)
        const val ENDHDR: Int = 22

        /* The following two fields are missing in SUN JDK */
        const val ENDNRD: Int = 4
        const val ENDDCD: Int = 6
        const val ENDSUB: Int = 8
        const val ENDTOT: Int = 10
        const val ENDSIZ: Int = 12
        const val ENDOFF: Int = 16
        const val ENDCOM: Int = 20
    }
}

