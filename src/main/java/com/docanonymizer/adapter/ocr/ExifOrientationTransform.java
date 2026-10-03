package com.docanonymizer.adapter.ocr;

import java.awt.image.BufferedImage;

/** Applies the standard EXIF Orientation pixel transform (values 2..8) to a decoded image. */
final class ExifOrientationTransform {

    private ExifOrientationTransform() {}

    static BufferedImage apply(BufferedImage source, int orientation) {
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swapDimensions = orientation >= 5 && orientation <= 8;
        BufferedImage result = new BufferedImage(
                swapDimensions ? height : width,
                swapDimensions ? width : height,
                BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = source.getRGB(x, y);
                setDestinationPixel(result, orientation, x, y, width, height, rgb);
            }
        }
        return result;
    }

    private static void setDestinationPixel(
            BufferedImage result, int orientation, int x, int y, int width, int height, int rgb) {
        switch (orientation) {
            case 2: // mirror horizontal
                result.setRGB(width - 1 - x, y, rgb);
                break;
            case 3: // rotate 180
                result.setRGB(width - 1 - x, height - 1 - y, rgb);
                break;
            case 4: // mirror vertical
                result.setRGB(x, height - 1 - y, rgb);
                break;
            case 5: // transpose
                result.setRGB(y, x, rgb);
                break;
            case 6: // rotate 90 CW
                result.setRGB(height - 1 - y, x, rgb);
                break;
            case 7: // transverse
                result.setRGB(height - 1 - y, width - 1 - x, rgb);
                break;
            case 8: // rotate 90 CCW (270 CW)
                result.setRGB(y, width - 1 - x, rgb);
                break;
            default: // orientation 1 or unknown: identity
                result.setRGB(x, y, rgb);
                break;
        }
    }
}
