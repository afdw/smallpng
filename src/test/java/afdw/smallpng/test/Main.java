package afdw.smallpng.test;

import afdw.smallpng.SmallPNG;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    private static final int ITERATIONS = 100;

    public static void main(String[] args) throws Exception {
        for (int n = 0; n < 2; n++) {
            int width = 256;
            int height = 256;
            ByteBuffer image = ByteBuffer.allocateDirect(width * height * 4);
            switch (n) {
                case 0:
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            image.position((width * y + x) * 4);
                            image.put((byte) x);
                            image.put((byte) y);
                            image.put((byte) (255 - x));
                            image.put((byte) 255);
                        }
                    }
                    break;
                case 1:
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            image.position((width * y + x) * 4);
                            image.put((byte) (x / 16 * 16));
                            image.put((byte) (x / 16 * 16));
                            image.put((byte) 0);
                            image.put(x > 127 ? (byte) 0 : (byte) 255);
                        }
                    }
                    break;
            }
            for (int i = 1; i <= ITERATIONS; i++) {
                long t = System.currentTimeMillis();
                try (OutputStream outputStream = new FileOutputStream(new File("test" + n + ".png"))) {
                    SmallPNG.write(outputStream, image, width, height);
                }
                if (i == ITERATIONS) {
                    System.out.println("Write " + n + ": " + (System.currentTimeMillis() - t) + "ms");
                }
            }
            for (int i = 1; i <= ITERATIONS; i++) {
                long t = System.currentTimeMillis();
                try (InputStream inputStream = new FileInputStream(new File("test" + n + ".png"))) {
                    AtomicInteger inWidth = new AtomicInteger();
                    AtomicInteger inHeight = new AtomicInteger();
                    ByteBuffer readImage = SmallPNG.read(inputStream, inWidth, inHeight);
                    int readWidth = inWidth.get();
                    int readHeight = inHeight.get();
                    if (width != readWidth) {
                        throw new AssertionError("width");
                    }
                    if (height != readHeight) {
                        throw new AssertionError("height");
                    }
                    if (image.capacity() != readImage.capacity()) {
                        throw new AssertionError("image size");
                    }
                    image.rewind();
                    readImage.rewind();
                    byte[] imageBuffer = new byte[image.capacity()];
                    byte[] readImageBuffer = new byte[readImage.capacity()];
                    image.get(imageBuffer);
                    readImage.get(readImageBuffer);
                    if (!Arrays.equals(imageBuffer, readImageBuffer)) {
                        BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                        int[] rgbArray = new int[width * height];
                        for (int j = 0; j < width * height; j++) {
                            int p = j * 4;
                            rgbArray[j] = (readImageBuffer[p + 3] & 0xFF) << 24 |
                                (readImageBuffer[p] & 0xFF) << 16 |
                                (readImageBuffer[p + 1] & 0xFF) << 8 |
                                readImageBuffer[p + 2] & 0xFF;
                        }
                        bufferedImage.setRGB(0, 0, width, height, rgbArray, 0, width);
                        JDialog dialog = new JDialog();
                        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                        dialog.add(new JLabel(new ImageIcon(bufferedImage)));
                        dialog.pack();
                        dialog.setVisible(true);
                        throw new AssertionError("image contents");
                    }
                }
                if (i == ITERATIONS) {
                    System.out.println("Read " + n + ": " + (System.currentTimeMillis() - t) + "ms");
                }
            }
        }
    }
}
