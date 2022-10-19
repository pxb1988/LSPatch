/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lsposed.lspatch.loader.util;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipException;

/**
 * This is code is get from Android 4.4.2 intent to read as more zip as possible
 * <p>
 * Ignore GPBF_ENCRYPTED_FLAG
 * <p>
 * Allow duplicate ZipEntry
 * <p>
 * Allow Nul byte in ZipEntry name
 */
public class FastZipIn implements Closeable {

    public static final long LOCSIG = 0x4034b50, CENSIG = 0x2014b50, ENDSIG = 0x06054b50;

    public static final int CENHDR = 46, ENDHDR = 22;

    /**
     * General Purpose Bit Flags, Bit 0. If set, indicates that the file is encrypted.
     */
    static final int GPBF_ENCRYPTED_FLAG = 1 << 0;

    /**
     * General Purpose Bit Flags, Bit 3. If this bit is set, the fields crc-32, compressed size and uncompressed size
     * are set to zero in the local header. The correct values are put in the data descriptor immediately following the
     * compressed data. (Note: PKZIP version 2.04g for DOS only recognizes this bit for method 8 compression, newer
     * versions of PKZIP recognize this bit for any compression method.)
     */
    static final int GPBF_DATA_DESCRIPTOR_FLAG = 1 << 3;

    /**
     * General Purpose Bit Flags, Bit 11. Language encoding flag (EFS). If this bit is set, the filename and comment
     * fields for this file must be encoded using UTF-8.
     */
    static final int GPBF_UTF8_FLAG = 1 << 11;

    /**
     * Supported General Purpose Bit Flags Mask. Bit mask of bits not supported. Note: The only bit that we will enforce
     * at this time is the encrypted bit. Although other bits are not supported, we must not enforce them as this could
     * break some legitimate use cases (See http://b/8617715).
     */
    static final int GPBF_UNSUPPORTED_MASK = GPBF_ENCRYPTED_FLAG;

    private List<FastZipEntry> entries;

    final ByteBuffer raf;
    RandomAccessFile file;
    long centralDirOffset = -1;

    public FastZipIn(File fd) throws IOException {
        RandomAccessFile randomAccessFile = new RandomAccessFile(fd, "r");
        file = randomAccessFile;
        raf = randomAccessFile.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fd.length()).order(ByteOrder.LITTLE_ENDIAN);
        readCentralDir();
    }

    public List<FastZipEntry> entries() {
        return entries;
    }

    public long getEntryDataStart(FastZipEntry entry) {
        int fileNameLength = raf.getShort((int) (entry.localHeaderRelOffset + 26)) & 0xffff;
        int extraFieldLength = raf.getShort((int) (entry.localHeaderRelOffset + 28)) & 0xffff;
        return entry.localHeaderRelOffset + 30 + fileNameLength + extraFieldLength;
    }

    static void skip(ByteBuffer is, int i) {
        is.position(is.position() + i);
    }

    /**
     * Find the central directory and read the contents.
     *
     * <p>
     * The central directory can be followed by a variable-length comment field, so we have to scan through it
     * backwards. The comment is at most 64K, plus we have 18 bytes for the end-of-central-dir stuff itself, plus
     * apparently sometimes people throw random junk on the end just for the fun of it.
     *
     * <p>
     * This is all a little wobbly. If the wrong value ends up in the EOCD area, we're hosed. This appears to be the way
     * that everybody handles it though, so we're in good company if this fails.
     */
    private void readCentralDir() throws IOException {
        ByteBuffer raf = this.raf;
        // Scan back, looking for the End Of Central Directory field. If the zip file doesn't
        // have an overall comment (unrelated to any per-entry comments), we'll hit the EOCD
        // on the first try.
        // No need to synchronize raf here -- we only do this when we first open the zip file.
        long scanOffset = raf.limit() - ENDHDR;
        if (scanOffset < 0) {
            throw new ZipException("File too short to be a zip file: " + raf.limit());
        }

        // not check Magic
        // raf.position(0);
        // final int headerMagic = raf.getInt();
        // if (headerMagic != LOCSIG) {
        // throw new ZipException("Not a zip archive");
        // }

        long stopOffset = scanOffset - 65536;
        if (stopOffset < 0) {
            stopOffset = 0;
        }

        while (true) {
            raf.position((int) scanOffset);
            if (raf.getInt() == ENDSIG) {
                break;
            }

            scanOffset--;
            if (scanOffset < stopOffset) {
                throw new ZipException("End Of Central Directory signature not found");
            }
        }

        // Read the End Of Central Directory. ENDHDR includes the signature bytes,
        // which we've already read.

        // Pull out the information we need.
        int diskNumber = raf.getShort() & 0xffff;
        int diskWithCentralDir = raf.getShort() & 0xffff;
        int numEntries = raf.getShort() & 0xffff;
        int totalNumEntries = raf.getShort() & 0xffff;
        skip(raf, 4); // Ignore centralDirSize.
        centralDirOffset = ((long) raf.getInt()) & 0xffffffffL;
        int commentLength = raf.getShort() & 0xffff;

        if (numEntries != totalNumEntries || diskNumber != 0 || diskWithCentralDir != 0) {
            throw new ZipException("Spanned archives not supported");
        }

        if (commentLength > 0) {
            skip(raf, commentLength);
        }

        // Seek to the first CDE and read all entries.
        // We have to do this now (from the constructor) rather than lazily because the
        // public API doesn't allow us to throw IOException except from the constructor
        // or from getInputStream.
        ByteBuffer buf = (ByteBuffer) raf.duplicate().order(ByteOrder.LITTLE_ENDIAN).position((int) centralDirOffset);
        entries = new ArrayList<>(numEntries);
        for (int i = 0; i < numEntries; ++i) {
            FastZipEntry newEntry = new FastZipEntry(buf);
            if (newEntry.localHeaderRelOffset >= centralDirOffset) {
                // Ignore the entry
                // throw new ZipException("Local file header offset is after central directory");
            } else {
                entries.add(newEntry);
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (file != null) {
            file.close();
        }
    }

}
