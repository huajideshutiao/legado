package me.ag2s.epublib.util.zip;

import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import me.ag2s.base.PfdHelper;

public class RemoteZipFile implements ZipConstants {

    private static final int EOCD_SIZE = 22;
    private static final int MAX_COMMENT_LENGTH = 65535;

    private final String name;
    private final ParcelFileDescriptor pfd;
    private final long fileSize;

    private HashMap<String, AndroidZipEntry> entries;
    private boolean closed = false;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public RemoteZipFile(@NonNull ParcelFileDescriptor pfd, String name, long fileSize) {
        this.pfd = pfd;
        this.name = name;
        this.fileSize = fileSize;
    }

    public Enumeration<AndroidZipEntry> entries() {
        try {
            ensureEntriesRead();
            return new ZipEntryEnumeration(entries.values().iterator());
        } catch (IOException ioe) {
            return null;
        }
    }

    public AndroidZipEntry getEntry(String name) {
        try {
            ensureEntriesRead();
            AndroidZipEntry entry = entries.get(name);
            return entry != null ? (AndroidZipEntry) entry.clone() : null;
        } catch (IOException ioe) {
            return null;
        }
    }

    public InputStream getInputStream(AndroidZipEntry entry) throws IOException {
        ensureEntriesRead();
        String entryName = entry.getName();
        AndroidZipEntry zipEntry = entries.get(entryName);
        if (zipEntry == null) {
            throw new java.util.NoSuchElementException(entryName);
        }

        long start = zipEntry.offset;
        byte[] lfh = readFully(start, LOCHDR);
        if (readLeInt(lfh, 0) != LOCSIG) {
            throw new IOException("Invalid Local File Header signature at offset " + start);
        }
        int nameLen = readLeShort(lfh, LOCNAM);
        int extraLen = readLeShort(lfh, LOCEXT);
        long dataOffset = start + LOCHDR + nameLen + extraLen;

        long compressedSize = zipEntry.getCompressedSize();

        int method = zipEntry.getMethod();
        InputStream is = new RemoteZipInputStream(pfd, dataOffset, compressedSize);

        return switch (method) {
            case 0 -> is;
            case 8 -> new InflaterInputStream(is, new Inflater(true));
            default -> throw new IOException("Unknown compression method " + method);
        };
    }

    private synchronized void ensureEntriesRead() throws IOException {
        if (initialized.get()) {
            return;
        }
        readEntries();
        initialized.set(true);
    }

    private void readEntries() throws IOException {
        long eocdPos = findEocd();
        if (eocdPos < 0) {
            throw new IOException("EOCD not found, not a valid ZIP file: " + name);
        }

        long centralDirOffset = readEocdAndGetCentralDirOffset(eocdPos);
        readCentralDirectory(centralDirOffset, eocdPos);
    }

    private long findEocd() throws IOException {
        long searchStart = Math.max(0, fileSize - EOCD_SIZE - MAX_COMMENT_LENGTH);
        int searchLen = (int) (fileSize - searchStart);

        byte[] searchBuffer = readFully(searchStart, searchLen);

        for (int i = searchLen - EOCD_SIZE; i >= 0; i--) {
            if (readLeInt(searchBuffer, i) == ENDSIG) {
                int commentLen = readLeShort(searchBuffer, i + EOCD_SIZE - 2);
                if (i + EOCD_SIZE + commentLen == fileSize) {
                    return searchStart + i;
                }
            }
        }

        return -1;
    }

    private long readEocdAndGetCentralDirOffset(long eocdPos) throws IOException {
        byte[] eocd = readFully(eocdPos, EOCD_SIZE);

        if (readLeInt(eocd, 0) != ENDSIG) {
            throw new IOException("Invalid EOCD signature");
        }

        return readLeInt(eocd, ENDOFF);
    }

    private void readCentralDirectory(long centralDirOffset, long eocdPos) throws IOException {
        long centralDirSize = eocdPos - centralDirOffset;
        if (centralDirSize <= 0 || centralDirSize > Integer.MAX_VALUE) {
            throw new IOException("Invalid central directory size");
        }

        byte[] centralDir = readFully(centralDirOffset, (int) centralDirSize);

        entries = new HashMap<>();
        int pos = 0;

        while (pos + CENHDR <= centralDirSize) {
            if (readLeInt(centralDir, pos) != CENSIG) {
                break;
            }

            int method = readLeShort(centralDir, pos + CENHOW);
            int dostime = readLeInt(centralDir, pos + CENTIM);
            int crc = readLeInt(centralDir, pos + CENCRC);
            long csize = readLeInt(centralDir, pos + CENSIZ) & 0xFFFFFFFFL;
            long size = readLeInt(centralDir, pos + CENLEN) & 0xFFFFFFFFL;
            int nameLen = readLeShort(centralDir, pos + CENNAM);
            int extraLen = readLeShort(centralDir, pos + CENEXT);
            int commentLen = readLeShort(centralDir, pos + CENCOM);
            int offset = readLeInt(centralDir, pos + CENOFF);

            String entryName = new String(centralDir, pos + CENHDR, nameLen, java.nio.charset.StandardCharsets.UTF_8);

            AndroidZipEntry entry = new AndroidZipEntry(entryName, nameLen);
            entry.setMethod(method);
            entry.setCrc(crc & 0xFFFFFFFFL);
            entry.setSize(size);
            entry.setCompressedSize(csize);
            entry.setTime(dostime);
            entry.offset = offset;

            entries.put(entryName, entry);

            pos += CENHDR + nameLen + extraLen + commentLen;
        }
    }

    private byte[] readFully(long offset, int length) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }

        byte[] result = new byte[length];
        PfdHelper.seek(pfd, offset);
        PfdHelper.readFully(pfd, result, 0, length);
        return result;
    }

    private int readLeInt(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xFF))
                | ((buffer[offset + 1] & 0xFF) << 8)
                | ((buffer[offset + 2] & 0xFF) << 16)
                | ((buffer[offset + 3] & 0xFF) << 24);
    }

    private int readLeShort(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8);
    }

    public void close() throws IOException {
        synchronized (pfd) {
            closed = true;
            entries = null;
            PfdHelper.seek(pfd, 0);
            pfd.close();
        }
    }

    protected void finalize() throws IOException {
        if (!closed) {
            close();
        }
    }

    public String getName() {
        return name;
    }

    public int size() {
        try {
            ensureEntriesRead();
            return entries.size();
        } catch (IOException ioe) {
            return 0;
        }
    }

    private record ZipEntryEnumeration(Iterator<AndroidZipEntry> elements)
            implements Enumeration<AndroidZipEntry> {

        public boolean hasMoreElements() {
            return elements.hasNext();
        }

        public AndroidZipEntry nextElement() {
            return (AndroidZipEntry) elements.next().clone();
        }
    }

    private static class RemoteZipInputStream extends InputStream {
        private final ParcelFileDescriptor pfd;
        private long filepos;
        private final long end;

        public RemoteZipInputStream(ParcelFileDescriptor pfd, long start, long len) {
            this.pfd = pfd;
            this.filepos = start;
            this.end = start + len;
        }

        @Override
        public int available() {
            long amount = end - filepos;
            if (amount > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) amount;
        }

        @Override
        public int read() throws IOException {
            if (filepos >= end) {
                return -1;
            }
            PfdHelper.seek(pfd, filepos);
            int result = PfdHelper.read(pfd);
            if (result >= 0) {
                filepos++;
            }
            return result;
        }

        @Override
        public int read(@NonNull byte[] b, int off, int len) throws IOException {
            if (len > end - filepos) {
                len = (int) (end - filepos);
                if (len == 0) {
                    return -1;
                }
            }
            PfdHelper.seek(pfd, filepos);
            int count = PfdHelper.read(pfd, b, off, len);
            if (count > 0) {
                filepos += count;
            }
            return count;
        }

        @Override
        public long skip(long amount) throws IOException {
            if (amount > end - filepos) {
                amount = end - filepos;
            }
            filepos += amount;
            return amount;
        }
    }
}
