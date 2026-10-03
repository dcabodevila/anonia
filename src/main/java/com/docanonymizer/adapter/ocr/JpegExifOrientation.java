package com.docanonymizer.adapter.ocr;

/** Reads the EXIF Orientation tag from a JPEG byte array, defaulting to 1 when absent or malformed. */
final class JpegExifOrientation {

    private static final int DEFAULT_ORIENTATION = 1;
    private static final int ORIENTATION_TAG = 0x0112;
    private static final int SHORT_TYPE = 3;

    private JpegExifOrientation() {}

    static int read(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4) {
            return DEFAULT_ORIENTATION;
        }
        if ((jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return DEFAULT_ORIENTATION;
        }

        int pos = 2;
        while (pos + 4 <= jpeg.length) {
            if ((jpeg[pos] & 0xFF) != 0xFF) {
                return DEFAULT_ORIENTATION;
            }
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2; // markers without a payload
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return DEFAULT_ORIENTATION; // EOI or SOS reached without finding APP1
            }
            int segmentLength = readUnsignedShort(jpeg, pos + 2, false); // marker length is always big-endian
            if (segmentLength < 2) {
                return DEFAULT_ORIENTATION;
            }
            int payloadStart = pos + 4;
            int payloadLength = segmentLength - 2;
            if (payloadStart + payloadLength > jpeg.length) {
                return DEFAULT_ORIENTATION;
            }
            if (marker == 0xE1 && isExifPayload(jpeg, payloadStart, payloadLength)) {
                return orientationFromExif(jpeg, payloadStart + 6, payloadLength - 6);
            }
            pos = payloadStart + payloadLength;
        }
        return DEFAULT_ORIENTATION;
    }

    private static boolean isExifPayload(byte[] jpeg, int payloadStart, int payloadLength) {
        if (payloadLength < 6) {
            return false;
        }
        return jpeg[payloadStart] == 'E'
                && jpeg[payloadStart + 1] == 'x'
                && jpeg[payloadStart + 2] == 'i'
                && jpeg[payloadStart + 3] == 'f'
                && jpeg[payloadStart + 4] == 0
                && jpeg[payloadStart + 5] == 0;
    }

    private static int orientationFromExif(byte[] jpeg, int tiffStart, int tiffLength) {
        if (tiffLength < 8) {
            return DEFAULT_ORIENTATION;
        }
        boolean littleEndian;
        if (jpeg[tiffStart] == 'I' && jpeg[tiffStart + 1] == 'I') {
            littleEndian = true;
        } else if (jpeg[tiffStart] == 'M' && jpeg[tiffStart + 1] == 'M') {
            littleEndian = false;
        } else {
            return DEFAULT_ORIENTATION;
        }
        int magic = readUnsignedShort(jpeg, tiffStart + 2, littleEndian);
        if (magic != 42) {
            return DEFAULT_ORIENTATION;
        }
        long ifd0Offset = readUnsignedInt(jpeg, tiffStart + 4, littleEndian);
        if (ifd0Offset < 0 || ifd0Offset + 2 > tiffLength) {
            return DEFAULT_ORIENTATION;
        }
        int ifd0Start = tiffStart + (int) ifd0Offset;
        int entryCount = readUnsignedShort(jpeg, ifd0Start, littleEndian);
        long entriesEnd = (long) ifd0Start + 2 + (long) entryCount * 12;
        if (entriesEnd > tiffStart + tiffLength) {
            return DEFAULT_ORIENTATION;
        }

        int entryStart = ifd0Start + 2;
        for (int i = 0; i < entryCount; i++) {
            int offset = entryStart + i * 12;
            int tag = readUnsignedShort(jpeg, offset, littleEndian);
            int type = readUnsignedShort(jpeg, offset + 2, littleEndian);
            if (tag == ORIENTATION_TAG && type == SHORT_TYPE) {
                int value = readUnsignedShort(jpeg, offset + 8, littleEndian);
                if (value >= 1 && value <= 8) {
                    return value;
                }
                return DEFAULT_ORIENTATION;
            }
        }
        return DEFAULT_ORIENTATION;
    }

    private static int readUnsignedShort(byte[] data, int offset, boolean littleEndian) {
        if (offset < 0 || offset + 2 > data.length) {
            return -1;
        }
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        return littleEndian ? (b1 << 8) | b0 : (b0 << 8) | b1;
    }

    private static long readUnsignedInt(byte[] data, int offset, boolean littleEndian) {
        if (offset < 0 || offset + 4 > data.length) {
            return -1;
        }
        long b0 = data[offset] & 0xFF;
        long b1 = data[offset + 1] & 0xFF;
        long b2 = data[offset + 2] & 0xFF;
        long b3 = data[offset + 3] & 0xFF;
        return littleEndian ? (b3 << 24) | (b2 << 16) | (b1 << 8) | b0 : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }
}
