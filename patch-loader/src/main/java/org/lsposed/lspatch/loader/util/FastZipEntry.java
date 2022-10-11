/*
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at
  <p>
  http://www.apache.org/licenses/LICENSE-2.0
  <p>
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package org.lsposed.lspatch.loader.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.zip.ZipException;

public final class FastZipEntry {

    public String name;
    public long crc = -1; // Needs to be a long to distinguish -1 ("not set") from the 0xffffffff CRC32.

    public long compressedSize = -1;
    public long unCompressSize = -1;

    public int compressionMethod = -1;
    public long modTime = -1;
    public int gpbf = 0;

    public long localHeaderRelOffset = -1;

    public FastZipEntry() {
    }
    /**
     * Zip entry state: DefAlated.
     */
    public static final int DEFLATED = 8;

    /**
     * Zip entry state: Stored.
     */
    public static final int STORED = 0;

    public void setTime(Date d) {
        int year = d.getYear() + 1900;
        long dostime = (year - 1980) << 25 | (d.getMonth() + 1) << 21 |
                d.getDate() << 16 | d.getHours() << 11 | d.getMinutes() << 5 |
                d.getSeconds() >> 1;
        this.modTime = dostime + ((d.getTime() % 2000) << 32);
    }

    FastZipEntry(ByteBuffer it0) throws IOException {
        ByteBuffer it = (ByteBuffer) it0.slice().order(ByteOrder.LITTLE_ENDIAN).limit(FastZipIn.CENHDR);
        FastZipIn.skip(it0, FastZipIn.CENHDR);
        int sig = it.getInt();
        if (sig != FastZipIn.CENSIG) {
            throw new ZipException("Central Directory Entry" + " signature not found; was " + String.format("0x%08x", sig));
        }

        it.position(8);
        gpbf = (it.getShort() & 0xffff) & FastZipIn.GPBF_UTF8_FLAG; // only support utf8 bit

        compressionMethod = it.getShort() & 0xffff;
        modTime = it.getInt() & 0xffffFFFFL;

        // These are 32-bit values in the file, but 64-bit fields in this object.
        crc = ((long) it.getInt()) & 0xffffffffL;
        compressedSize = ((long) it.getInt()) & 0xffffffffL;
        unCompressSize = ((long) it.getInt()) & 0xffffffffL;

        if (compressionMethod == STORED) {
            if (compressedSize != unCompressSize) {
                throw new ZipException("fai compress");
            }
        }

        int nameLength = it.getShort() & 0xffff;
        int extraLength = it.getShort() & 0xffff;
        int commentByteCount = it.getShort() & 0xffff;

        // This is a 32-bit value in the file, but a 64-bit field in this object.
        it.position(42);
        localHeaderRelOffset = ((long) it.getInt()) & 0xffffffffL;

        byte[] nameBytes = new byte[nameLength];
        it0.get(nameBytes);
        // if (containsNulByte(nameBytes)) {
        // throw new ZipException("Filename contains NUL byte: " + Arrays.toString(nameBytes));
        // }
        name = new String(nameBytes, StandardCharsets.UTF_8);

        if (extraLength > 0) {
            FastZipIn.skip(it0, extraLength);
        }

        // The RI has always assumed UTF-8. (If GPBF_UTF8_FLAG isn't set, the encoding is
        // actually IBM-437.)
        if (commentByteCount > 0) {
            FastZipIn.skip(it0, commentByteCount);
        }
    }
}
