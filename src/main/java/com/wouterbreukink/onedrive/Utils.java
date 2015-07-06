package com.wouterbreukink.onedrive;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

public class Utils {

    /**
     * Get the CRC32 Checksum for a file
     *
     * @param file The file to check
     * @return The CRC32 checksum of the file
     * @throws IOException
     */
    static long getChecksum(File file) throws IOException {

        // Compute CRC32 checksum
        CheckedInputStream cis = new CheckedInputStream(new FileInputStream(file), new CRC32());
        byte[] buf = new byte[128];
        while (cis.read(buf) >= 0) {
        }

        return cis.getChecksum().getValue();
    }
}