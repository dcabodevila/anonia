package com.docanonymizer.adapter.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class ExifOrientationTransformTest {

    /**
     * Source image is 2 wide x 3 tall with distinct corner colors:
     * top-left=RED, top-right=GREEN, bottom-left=BLUE, bottom-right=WHITE.
     */
    private BufferedImage source() {
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFF0000); // top-left RED
        image.setRGB(1, 0, 0x00FF00); // top-right GREEN
        image.setRGB(0, 2, 0x0000FF); // bottom-left BLUE
        image.setRGB(1, 2, 0xFFFFFF); // bottom-right WHITE
        return image;
    }

    @Test
    void orientation3Rotates180() {
        BufferedImage result = ExifOrientationTransform.apply(source(), 3);

        assertEquals(2, result.getWidth());
        assertEquals(3, result.getHeight());
        assertEquals(0xFFFFFF, result.getRGB(0, 0) & 0xFFFFFF); // was bottom-right
        assertEquals(0xFF0000, result.getRGB(1, 2) & 0xFFFFFF); // was top-left
    }

    @Test
    void orientation6RotatesNinetyClockwiseAndSwapsDimensions() {
        BufferedImage result = ExifOrientationTransform.apply(source(), 6);

        assertEquals(3, result.getWidth());
        assertEquals(2, result.getHeight());
        // top-left of source moves to top-right after 90 CW rotation
        assertEquals(0xFF0000, result.getRGB(2, 0) & 0xFFFFFF);
        // bottom-left of source moves to top-left after 90 CW rotation
        assertEquals(0x0000FF, result.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void orientation8RotatesNinetyCounterClockwiseAndSwapsDimensions() {
        BufferedImage result = ExifOrientationTransform.apply(source(), 8);

        assertEquals(3, result.getWidth());
        assertEquals(2, result.getHeight());
        // top-right of source moves to top-left after 90 CCW rotation
        assertEquals(0x00FF00, result.getRGB(0, 0) & 0xFFFFFF);
        // top-left of source moves to bottom-left after 90 CCW rotation
        assertEquals(0xFF0000, result.getRGB(0, 1) & 0xFFFFFF);
    }

    @Test
    void orientation2MirrorsHorizontally() {
        BufferedImage result = ExifOrientationTransform.apply(source(), 2);

        assertEquals(2, result.getWidth());
        assertEquals(3, result.getHeight());
        assertEquals(0x00FF00, result.getRGB(0, 0) & 0xFFFFFF); // top-right moved to top-left
        assertEquals(0xFF0000, result.getRGB(1, 0) & 0xFFFFFF); // top-left moved to top-right
    }
}
