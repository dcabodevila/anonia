package com.docanonymizer.adapter.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class JpegExifOrientationTest {

    @Test
    void readsOrientationFromLittleEndianTiffHeader() {
        byte[] jpeg = jpegWithApp1(exifSegment(true, 6), false);
        assertEquals(6, JpegExifOrientation.read(jpeg));
    }

    @Test
    void readsOrientationFromBigEndianTiffHeader() {
        byte[] jpeg = jpegWithApp1(exifSegment(false, 3), false);
        assertEquals(3, JpegExifOrientation.read(jpeg));
    }

    @Test
    void readsOrientationWhenJfifApp0PrecedesApp1() {
        byte[] jpeg = jpegWithApp1(exifSegment(true, 8), true);
        assertEquals(8, JpegExifOrientation.read(jpeg));
    }

    @Test
    void defaultsToOneWhenNoApp1SegmentPresent() {
        byte[] jpeg = jpegWithoutExif();
        assertEquals(1, JpegExifOrientation.read(jpeg));
    }

    @Test
    void defaultsToOneForNonJpegBytes() {
        byte[] bytes = new byte[] {0x00, 0x01, 0x02, 0x03};
        assertEquals(1, JpegExifOrientation.read(bytes));
    }

    @Test
    void defaultsToOneWhenIfdOffsetIsBeyondSegmentLength() {
        byte[] exif = exifSegment(true, 6);
        // Corrupt the IFD0 offset (bytes 4..7 of the TIFF header) to point far beyond the buffer.
        int tiffStart = 6; // "Exif\0\0" prefix length
        exif[tiffStart + 4] = (byte) 0xFF;
        exif[tiffStart + 5] = (byte) 0xFF;
        exif[tiffStart + 6] = (byte) 0xFF;
        exif[tiffStart + 7] = (byte) 0xFF;
        byte[] jpeg = jpegWithApp1(exif, false);
        assertEquals(1, JpegExifOrientation.read(jpeg));
    }

    @Test
    void defaultsToOneWhenEntryCountIsAbsurd() {
        byte[] exif = exifSegment(true, 6);
        int tiffStart = 6;
        int ifd0Offset = 8; // as written by exifSegment
        int entryCountIndex = tiffStart + ifd0Offset;
        exif[entryCountIndex] = (byte) 0xFF;
        exif[entryCountIndex + 1] = (byte) 0xFF;
        byte[] jpeg = jpegWithApp1(exif, false);
        assertEquals(1, JpegExifOrientation.read(jpeg));
    }

    @Test
    void defaultsToOneWhenOrientationValueIsZero() {
        byte[] jpeg = jpegWithApp1(exifSegment(true, 0), false);
        assertEquals(1, JpegExifOrientation.read(jpeg));
    }

    @Test
    void defaultsToOneWhenOrientationValueIsNine() {
        byte[] jpeg = jpegWithApp1(exifSegment(true, 9), false);
        assertEquals(1, JpegExifOrientation.read(jpeg));
    }

    /** Builds an APP1 payload: "Exif\0\0" + TIFF header + IFD0 with one Orientation entry. */
    private static byte[] exifSegment(boolean littleEndian, int orientation) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('E');
        out.write('x');
        out.write('i');
        out.write('f');
        out.write(0);
        out.write(0);

        int tiffStart = out.size();
        if (littleEndian) {
            out.write('I');
            out.write('I');
            writeShort(out, 42, true);
            writeInt(out, 8, true); // IFD0 offset relative to tiffStart
        } else {
            out.write('M');
            out.write('M');
            writeShort(out, 42, false);
            writeInt(out, 8, false);
        }
        // tiffStart + 8 is reached now (2 + 2 + 4 bytes written since tiffStart).
        writeShort(out, 1, littleEndian); // one IFD0 entry
        writeShort(out, 0x0112, littleEndian); // tag: Orientation
        writeShort(out, 3, littleEndian); // type: SHORT
        writeInt(out, 1, littleEndian); // count
        writeShort(out, orientation, littleEndian); // value (first 2 bytes of the 4-byte field)
        writeShort(out, 0, littleEndian); // padding to fill the 4-byte value field
        writeInt(out, 0, littleEndian); // next IFD offset
        return out.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream out, int value, boolean littleEndian) {
        int hi = (value >> 8) & 0xFF;
        int lo = value & 0xFF;
        if (littleEndian) {
            out.write(lo);
            out.write(hi);
        } else {
            out.write(hi);
            out.write(lo);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value, boolean littleEndian) {
        int b0 = (value >> 24) & 0xFF;
        int b1 = (value >> 16) & 0xFF;
        int b2 = (value >> 8) & 0xFF;
        int b3 = value & 0xFF;
        if (littleEndian) {
            out.write(b3);
            out.write(b2);
            out.write(b1);
            out.write(b0);
        } else {
            out.write(b0);
            out.write(b1);
            out.write(b2);
            out.write(b3);
        }
    }

    private static byte[] jpegWithApp1(byte[] exifPayload, boolean withJfifApp0) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8); // SOI
        if (withJfifApp0) {
            byte[] jfif = new byte[] {
                'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0
            };
            writeMarkerSegment(out, 0xE0, jfif);
        }
        writeMarkerSegment(out, 0xE1, exifPayload);
        out.write(0xFF);
        out.write(0xD9); // EOI
        return out.toByteArray();
    }

    private static byte[] jpegWithoutExif() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8); // SOI
        byte[] jfif = new byte[] {
            'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0
        };
        writeMarkerSegment(out, 0xE0, jfif);
        out.write(0xFF);
        out.write(0xD9); // EOI
        return out.toByteArray();
    }

    private static void writeMarkerSegment(ByteArrayOutputStream out, int marker, byte[] payload) {
        out.write(0xFF);
        out.write(marker);
        int length = payload.length + 2; // length field includes itself
        writeShort(out, length, false);
        out.write(payload, 0, payload.length);
    }
}
