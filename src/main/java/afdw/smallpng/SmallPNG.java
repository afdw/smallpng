package afdw.smallpng;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public class SmallPNG {
    private static final byte[] SIGNATURE = {
        (byte) 137,
        (byte) 80,
        (byte) 78,
        (byte) 71,
        (byte) 13,
        (byte) 10,
        (byte) 26,
        (byte) 10
    };
    private static final byte[] CHUNK_TYPE_IHDR = new byte[] {(byte) 73, (byte) 72, (byte) 68, (byte) 82};
    private static final byte[] CHUNK_TYPE_PLTE = new byte[] {(byte) 80, (byte) 76, (byte) 84, (byte) 69};
    private static final byte[] CHUNK_TYPE_tRNS = new byte[] {(byte) 116, (byte) 82, (byte) 78, (byte) 83};
    private static final byte[] CHUNK_TYPE_IDAT = new byte[] {(byte) 73, (byte) 68, (byte) 65, (byte) 84};
    private static final byte[] CHUNK_TYPE_IEND = new byte[] {(byte) 73, (byte) 69, (byte) 78, (byte) 68};

    private static byte[] readBytes(InputStream inputStream, int count) throws IOException {
        byte[] buffer = new byte[count];
        int read = 0;
        while (read < count) {
            int current = inputStream.read(buffer, read, count - read);
            if (current == -1) {
                throw new EOFException();
            }
            read += current;
        }
        return buffer;
    }

    private static void validateChunkType(byte[] chunkType) throws IOException {
        if (chunkType.length != 4) {
            throw new IOException("Invalid chunk type length");
        }
        for (byte b : chunkType) {
            if ((b < 65 || b > 90) && (b < 97 || b > 122)) {
                throw new IOException("Invalid chunk type byte");
            }
        }
    }

    private static void writeChunk(OutputStream outputStream,
                                   byte[] chunkType,
                                   byte[] chunkData) throws IOException {
        int length = chunkData.length;
        validateChunkType(chunkType);
        ByteBuffer crcBuffer = ByteBuffer.allocateDirect(chunkType.length + chunkData.length);
        crcBuffer.put(chunkType);
        crcBuffer.put(chunkData);
        crcBuffer.rewind();
        CRC32 crc32 = new CRC32();
        crc32.update(crcBuffer);
        int crc = (int) crc32.getValue();
        outputStream.write(new byte[] {
            (byte) (length >> 24),
            (byte) (length >> 16),
            (byte) (length >> 8),
            (byte) (length)
        });
        outputStream.write(chunkType);
        outputStream.write(chunkData);
        outputStream.write(new byte[] {
            (byte) (crc >> 24),
            (byte) (crc >> 16),
            (byte) (crc >> 8),
            (byte) (crc)
        });
    }

    private static void readChunk(InputStream inputStream,
                                  AtomicReference<byte[]> outChunkType,
                                  AtomicReference<byte[]> outChunkData) throws IOException {
        byte[] lengthRead = readBytes(inputStream, 4);
        int length = (lengthRead[0] & 0xFF) << 24 |
            (lengthRead[1] & 0xFF) << 16 |
            (lengthRead[2] & 0xFF) << 8 |
            (lengthRead[3] & 0xFF);
        byte[] chunkType = readBytes(inputStream, 4);
        validateChunkType(chunkType);
        byte[] chunkData = readBytes(inputStream, length);
        byte[] crcRead = readBytes(inputStream, 4);
        int crc = (crcRead[0] & 0xFF) << 24 |
            (crcRead[1] & 0xFF) << 16 |
            (crcRead[2] & 0xFF) << 8 |
            (crcRead[3] & 0xFF);
        validateChunkType(chunkType);
        ByteBuffer crcBuffer = ByteBuffer.allocateDirect(chunkType.length + chunkData.length);
        crcBuffer.put(chunkType);
        crcBuffer.put(chunkData);
        crcBuffer.rewind();
        CRC32 crc32 = new CRC32();
        crc32.update(crcBuffer);
        if (crc != (int) crc32.getValue()) {
            throw new IOException("Invalid CRC");
        }
        outChunkType.set(chunkType);
        outChunkData.set(chunkData);
    }

    public static void write(OutputStream outputStream,
                             ByteBuffer image,
                             int width,
                             int height) throws IOException {
        boolean greyscale = true;
        boolean alpha = false;
        EncodePalette palette = new EncodePalette();
        image.rewind();
        for (int i = 0; i < image.capacity(); i += 4) {
            byte[] pixel = new byte[4];
            image.get(pixel);
            if (pixel[0] != pixel[1] || pixel[2] != pixel[3]) {
                greyscale = false;
            }
            if (pixel[3] != (byte) 255) {
                alpha = true;
            }
            palette.addColor(pixel);
        }
        palette.sort();
        ColorType nonFinalColorType;
        byte nonFinalBitDepth;
        if (!palette.overflow && palette.size <= 2) {
            nonFinalColorType = ColorType.INDEXED;
            nonFinalBitDepth = (byte) 1;
        } else if (!palette.overflow && palette.size <= 4) {
            nonFinalColorType = ColorType.INDEXED;
            nonFinalBitDepth = (byte) 2;
        } else if (!palette.overflow && palette.size <= 16) {
            nonFinalColorType = ColorType.INDEXED;
            nonFinalBitDepth = (byte) 4;
        } else if (!palette.overflow && palette.size <= 256) {
            nonFinalColorType = ColorType.INDEXED;
            nonFinalBitDepth = (byte) 8;
        } else if (!alpha) {
            if (greyscale) {
                nonFinalColorType = ColorType.GREYSCALE;
                nonFinalBitDepth = (byte) 8;
            } else {
                nonFinalColorType = ColorType.TRUECOLOR;
                nonFinalBitDepth = (byte) 8;
            }
        } else {
            if (greyscale) {
                nonFinalColorType = ColorType.GREYSCALE_WITH_ALPHA;
                nonFinalBitDepth = (byte) 8;
            } else {
                nonFinalColorType = ColorType.TRUECOLOR_WITH_ALPHA;
                nonFinalBitDepth = (byte) 8;
            }
        }
        ColorType colorType = nonFinalColorType;
        byte bitDepth = nonFinalBitDepth;
        colorType.validate(bitDepth);
        InterlaceMethod interlaceMethod = InterlaceMethod.NONE;
        outputStream.write(SIGNATURE);
        writeChunk(
            outputStream,
            CHUNK_TYPE_IHDR,
            new byte[] {
                (byte) (width >> 24),
                (byte) (width >> 16),
                (byte) (width >> 8),
                (byte) width,
                (byte) (height >> 24),
                (byte) (height >> 16),
                (byte) (height >> 8),
                (byte) height,
                bitDepth,
                colorType.id,
                CompressionMethod.DEFLATE.id,
                FilterMethod.ADAPTIVE.id,
                interlaceMethod.id
            }
        );
        if (colorType == ColorType.INDEXED) {
            {
                byte[] data = new byte[palette.size * 3];
                int pos = 0;
                for (byte[] color : palette) {
                    System.arraycopy(color, 0, data, pos, 3);
                    pos += 3;
                }
                writeChunk(
                    outputStream,
                    CHUNK_TYPE_PLTE,
                    data
                );
            }
            if (alpha) {
                byte[] data = new byte[palette.alphaSize];
                int i = 0;
                for (byte[] color : palette) {
                    if (i < palette.alphaSize) {
                        data[i] = color[3];
                    }
                    i++;
                }
                writeChunk(
                    outputStream,
                    CHUNK_TYPE_tRNS,
                    data
                );
            }
        }
        {
            List<byte[]> rowsData = new ArrayList<>();
            interlaceMethod.encode(
                image,
                width,
                height,
                (passImage, passWidth, passHeight) -> {
                    byte[][] passBytes = new byte[passHeight][];
                    for (int y = 0; y < passHeight; y++) {
                        passImage.position((y * passWidth) * 4);
                        byte[] row = new byte[passWidth * 4];
                        passImage.get(row);
                        passBytes[y] = colorType.toRowBytes(
                            palette,
                            passWidth,
                            bitDepth,
                            row
                        );
                    }
                    for (int y = 0; y < passHeight; y++) {
                        FilterType minFilterType = null;
                        byte[] minFilteredRowBytes = null;
                        int minSum = Integer.MAX_VALUE;
                        for (FilterType filterType : FilterType.values()) {
                            byte[] filteredRowBytes = filterType.filter(
                                passBytes[y],
                                y > 0 ? passBytes[y - 1] : null,
                                colorType.getPixelBytesCount(bitDepth)
                            );
                            int sum = 0;
                            for (byte filteredRowByte : filteredRowBytes) {
                                sum += Math.abs(filteredRowByte);
                            }
                            if (minFilteredRowBytes == null || sum < minSum) {
                                minFilterType = filterType;
                                minFilteredRowBytes = filteredRowBytes;
                                minSum = sum;
                            }
                        }
                        if (minFilteredRowBytes == null) {
                            throw new IOException();
                        }
                        byte[] rowData = new byte[1 + minFilteredRowBytes.length];
                        rowData[0] = minFilterType.id;
                        System.arraycopy(minFilteredRowBytes, 0, rowData, 1, minFilteredRowBytes.length);
                        rowsData.add(rowData);
                    }
                }
            );
            int dataSize = 0;
            for (byte[] rowData : rowsData) {
                dataSize += rowData.length;
            }
            ByteBuffer data = ByteBuffer.allocate(dataSize);
            rowsData.forEach(data::put);
            try (OutputStream chunkOutputStream = new OutputStream() {
                private byte[] buffer = new byte[1024];
                private int pos = 0;

                private void write() throws IOException {
                    writeChunk(
                        outputStream,
                        CHUNK_TYPE_IDAT,
                        buffer
                    );
                }

                @Override
                public void write(int b) throws IOException {
                    buffer[pos++] = (byte) b;
                    if (pos >= buffer.length) {
                        write();
                        buffer = new byte[buffer.length];
                        pos = 0;
                    }
                }

                @Override
                public void close() throws IOException {
                    buffer = Arrays.copyOf(buffer, pos);
                    pos = 0;
                    if (buffer.length > 0) {
                        write();
                    }
                }
            }) {
                try (DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(
                    chunkOutputStream,
                    new Deflater(Deflater.BEST_COMPRESSION))) {
                    deflaterOutputStream.write(data.array());
                }
            }
        }
        writeChunk(
            outputStream,
            CHUNK_TYPE_IEND,
            new byte[0]
        );
    }

    public static ByteBuffer read(InputStream inputStream,
                                  AtomicInteger outWidth,
                                  AtomicInteger outHeight) throws IOException {
        int nonFinalWidth = 0;
        int nonFinalHeight = 0;
        byte nonFinalBitDepth = 0;
        ColorType nonFinalColorType = null;
        CompressionMethod nonFinalCompressionMethod = null;
        FilterMethod nonFinalFilterMethod = null;
        InterlaceMethod nonFinalInterlaceMethod = null;
        byte[][] nonFinalPalette = null;
        if (!Arrays.equals(SIGNATURE, readBytes(inputStream, SIGNATURE.length))) {
            throw new IOException("Invalid signature");
        }
        List<byte[]> readChunksTypes = new ArrayList<>();
        Predicate<byte[]> isRead = (byte[] chunkType) -> {
            for (byte[] readChunkType : readChunksTypes) {
                if (Arrays.equals(readChunkType, chunkType)) {
                    return true;
                }
            }
            return false;
        };
        List<byte[]> imageDataChunks = new ArrayList<>();
        while (true) {
            AtomicReference<byte[]> inChunkType = new AtomicReference<>();
            AtomicReference<byte[]> inChunkData = new AtomicReference<>();
            readChunk(inputStream, inChunkType, inChunkData);
            byte[] chunkType = inChunkType.get();
            byte[] chunkData = inChunkData.get();
            if (readChunksTypes.isEmpty()) {
                if (!Arrays.equals(chunkType, CHUNK_TYPE_IHDR)) {
                    throw new IOException("IHDR expected");
                }
                nonFinalWidth = (chunkData[0] & 0xFF) << 24 |
                    (chunkData[1] & 0xFF) << 16 |
                    (chunkData[2] & 0xFF) << 8 |
                    (chunkData[3] & 0xFF);
                nonFinalHeight = (chunkData[4] & 0xFF) << 24 |
                    (chunkData[5] & 0xFF) << 16 |
                    (chunkData[6] & 0xFF) << 8 |
                    (chunkData[7] & 0xFF);
                nonFinalBitDepth = chunkData[8];
                nonFinalColorType = ColorType.getById(chunkData[9]);
                nonFinalCompressionMethod = CompressionMethod.getById(chunkData[10]);
                nonFinalFilterMethod = FilterMethod.getById(chunkData[11]);
                nonFinalInterlaceMethod = InterlaceMethod.getById(chunkData[12]);
            } else if (Arrays.equals(chunkType, CHUNK_TYPE_IHDR)) {
                throw new IOException("IHDR read more then once");
            }
            if (Arrays.equals(chunkType, CHUNK_TYPE_PLTE)) {
                if (nonFinalColorType != ColorType.INDEXED) {
                    throw new IOException("PLTE read, but color type is not indexed");
                }
                if (isRead.test(CHUNK_TYPE_PLTE)) {
                    throw new IOException("PLTE read more then once");
                }
                if (chunkData.length % 3 != 0) {
                    throw new IOException("PLTE data size is not dividable by 3");
                }
                if (chunkData.length / 3 > 256) {
                    throw new IOException("PLTE data is too big");
                }
                nonFinalPalette = new byte[chunkData.length / 3][4];
                for (int i = 0; i < chunkData.length / 3; i++) {
                    System.arraycopy(chunkData, i * 3, nonFinalPalette[i], 0, 3);
                    nonFinalPalette[i][3] = (byte) 255;
                }
            }
            if (Arrays.equals(chunkType, CHUNK_TYPE_tRNS)) {
                if (nonFinalColorType != ColorType.INDEXED) {
                    throw new IOException("tRNS read, but color type is not indexed");
                }
                if (isRead.test(CHUNK_TYPE_tRNS)) {
                    throw new IOException("tRNS read more then once");
                }
                if (!isRead.test(CHUNK_TYPE_PLTE)) {
                    throw new IOException("PLTE expected before tRNS");
                }
                if (chunkData.length > Objects.requireNonNull(nonFinalPalette).length) {
                    throw new IOException("tRNS is bigger then PLTE");
                }
                int i = 0;
                for (byte alpha : chunkData) {
                    Objects.requireNonNull(nonFinalPalette)[i][3] = alpha;
                    i++;
                }
            }
            if (Arrays.equals(chunkType, CHUNK_TYPE_IDAT)) {
                if (nonFinalColorType == ColorType.INDEXED && !isRead.test(CHUNK_TYPE_PLTE)) {
                    throw new IOException("PLTE not read, but color type is indexed");
                }
                if (isRead.test(CHUNK_TYPE_IDAT) &&
                    !Arrays.equals(readChunksTypes.get(readChunksTypes.size() - 1), CHUNK_TYPE_IDAT)) {
                    throw new IOException("IDAT's should be consecutive");
                }
                imageDataChunks.add(chunkData);
            }
            if (Arrays.equals(chunkType, CHUNK_TYPE_IEND)) {
                if (!isRead.test(CHUNK_TYPE_IDAT)) {
                    throw new IOException("No IDAT's read");
                }
                break;
            }
            readChunksTypes.add(chunkType);
        }
        int width = nonFinalWidth;
        int height = nonFinalHeight;
        byte bitDepth = Objects.requireNonNull(nonFinalBitDepth);
        ColorType colorType = Objects.requireNonNull(nonFinalColorType);
        CompressionMethod compressionMethod = Objects.requireNonNull(nonFinalCompressionMethod);
        FilterMethod filterMethod = Objects.requireNonNull(nonFinalFilterMethod);
        InterlaceMethod interlaceMethod = Objects.requireNonNull(nonFinalInterlaceMethod);
        byte[][] palette = nonFinalPalette != null ? nonFinalPalette : new byte[0][];
        outWidth.set(width);
        outHeight.set(height);
        if (compressionMethod != CompressionMethod.DEFLATE) {
            throw new UnsupportedEncodingException();
        }
        if (filterMethod != FilterMethod.ADAPTIVE) {
            throw new UnsupportedEncodingException();
        }
        int imageDataLength = 0;
        for (byte[] imageDataChunk : imageDataChunks) {
            imageDataLength += imageDataChunk.length;
        }
        ByteBuffer imageData = ByteBuffer.allocate(imageDataLength);
        for (byte[] imageDataChunk : imageDataChunks) {
            imageData.put(imageDataChunk);
        }
        try (InflaterInputStream inflaterInputStream = new InflaterInputStream(
            new ByteArrayInputStream(imageData.array())
        )) {
            return interlaceMethod.decode(
                width,
                height,
                (passWidth, passHeight) -> {
                    ByteBuffer passImage = ByteBuffer.allocateDirect(passWidth * passHeight * 4);
                    byte[] prevReconstructedRow = null;
                    for (int i = 0; i < passHeight; i++) {
                        byte[] rowData = readBytes(
                            inflaterInputStream,
                            1 + colorType.getRowBytesCount(passWidth, bitDepth)
                        );
                        FilterType filterType = FilterType.getById(rowData[0]);
                        byte[] reconstructedRow = filterType.reconstruct(
                            Arrays.copyOfRange(rowData, 1, rowData.length),
                            prevReconstructedRow,
                            colorType.getPixelBytesCount(bitDepth)
                        );
                        prevReconstructedRow = reconstructedRow;
                        passImage.put(colorType.fromRowBytes(
                            palette,
                            passWidth,
                            bitDepth,
                            reconstructedRow
                        ));
                    }
                    return passImage;
                }
            );
        }
    }

    private enum ColorType {
        GREYSCALE((byte) 0, 1, (byte) 1, (byte) 2, (byte) 4, (byte) 8, (byte) 16) {
            @Override
            public byte[] toType(EncodePalette palette, byte[] pixel) {
                return new byte[] {(byte) (((pixel[0] & 0xFF) + (pixel[1] & 0xFF) + (pixel[2] & 0xFF)) / 3)};
            }

            @Override
            public byte[] fromType(byte[][] palette, byte[] typedPixel) {
                return new byte[] {typedPixel[0], typedPixel[0], typedPixel[0], (byte) 255};
            }
        },
        TRUECOLOR((byte) 2, 3, (byte) 8, (byte) 16) {
            @Override
            public byte[] toType(EncodePalette palette, byte[] pixel) {
                return new byte[] {pixel[0], pixel[1], pixel[2]};
            }

            @Override
            public byte[] fromType(byte[][] palette, byte[] typedPixel) {
                return new byte[] {typedPixel[0], typedPixel[1], typedPixel[2], (byte) 255};
            }
        },
        INDEXED((byte) 3, 1, (byte) 1, (byte) 2, (byte) 4, (byte) 8) {
            @Override
            public byte[] toType(EncodePalette palette, byte[] pixel) {
                return new byte[] {palette.getIndex(pixel)};
            }

            @Override
            public byte[] fromType(byte[][] palette, byte[] typedPixel) {
                return palette[(typedPixel[0] & 0xFF)];
            }
        },
        GREYSCALE_WITH_ALPHA((byte) 4, 2, (byte) 8, (byte) 16) {
            @Override
            public byte[] toType(EncodePalette palette, byte[] pixel) {
                return new byte[] {(byte) (((pixel[0] & 0xFF) + (pixel[1] & 0xFF) + (pixel[2] & 0xFF)) / 3), pixel[3]};
            }

            @Override
            public byte[] fromType(byte[][] palette, byte[] typedPixel) {
                return new byte[] {typedPixel[0], typedPixel[0], typedPixel[0], typedPixel[1]};
            }
        },
        TRUECOLOR_WITH_ALPHA((byte) 6, 4, (byte) 8, (byte) 16) {
            @Override
            public byte[] toType(EncodePalette palette, byte[] pixel) {
                return new byte[] {pixel[0], pixel[1], pixel[2], pixel[3]};
            }

            @Override
            public byte[] fromType(byte[][] palette, byte[] typedPixel) {
                return new byte[] {typedPixel[0], typedPixel[1], typedPixel[2], typedPixel[3]};
            }
        };

        public final byte id;
        public final int componentsCount;
        public final List<Byte> allowedBitDepths;

        ColorType(byte id, int componentsCount, Byte... allowedBitDepths) {
            this.id = id;
            this.componentsCount = componentsCount;
            this.allowedBitDepths = Arrays.asList(allowedBitDepths);
        }

        public static ColorType getById(byte id) throws IOException {
            for (ColorType colorType : values()) {
                if (colorType.id == id) {
                    return colorType;
                }
            }
            throw new IOException("Invalid color type");
        }

        public void validate(byte bitDepth) throws IOException {
            if (!allowedBitDepths.contains(bitDepth)) {
                throw new IOException("Invalid bit depth");
            }
        }

        private static int ceilDivide(int a, int b) {
            return a / b + ((a % b == 0) ? 0 : 1);
        }

        public int getPixelBytesCount(byte bitDepth) {
            return (bitDepth < Byte.SIZE ? 1 : bitDepth / Byte.SIZE) * componentsCount;
        }

        public int getRowBytesCount(int width, byte bitDepth) {
            return ceilDivide(width * componentsCount * bitDepth, Byte.SIZE);
        }

        public abstract byte[] toType(EncodePalette palette, byte[] pixel);

        public abstract byte[] fromType(byte[][] palette, byte[] typedPixel);

        public byte[] toRowBytes(EncodePalette palette, int width, byte bitDepth, byte[] row) {
            byte[] rowBytes = new byte[getRowBytesCount(width, bitDepth)];
            int bytePos = 0;
            int bitPos = 0;
            for (int i = 0; i < width * 4; i += 4) {
                byte[] pixel = Arrays.copyOfRange(row, i, i + 4);
                byte[] typedPixel = toType(palette, pixel);
                byte[] multipliedPixel = typedPixel;
                if (bitDepth == 16) {
                    multipliedPixel = new byte[typedPixel.length * 2];
                    for (int j = 0; j < typedPixel.length; j++) {
                        multipliedPixel[j * 2] = typedPixel[j];
                        multipliedPixel[j * 2 + 1] = (byte) 0;
                    }
                } else if (this != INDEXED && bitDepth < Byte.SIZE) {
                    multipliedPixel = new byte[typedPixel.length];
                    for (int j = 0; j < typedPixel.length; j++) {
                        multipliedPixel[j] = (byte) ((typedPixel[j] & 0xFF) >> (Byte.SIZE - bitDepth));
                    }
                }
                if (bitDepth % Byte.SIZE == 0 && bitDepth >= Byte.SIZE) {
                    System.arraycopy(multipliedPixel, 0, rowBytes, bytePos, multipliedPixel.length);
                    bytePos += multipliedPixel.length;
                } else if (Byte.SIZE % bitDepth == 0 && bitDepth < Byte.SIZE) {
                    for (byte b : multipliedPixel) {
                        rowBytes[bytePos] |= b << (Byte.SIZE - bitDepth - bitPos);
                        bitPos += bitDepth;
                        if (bitPos == Byte.SIZE) {
                            bitPos = 0;
                            bytePos++;
                        }
                    }
                }
            }
            return rowBytes;
        }

        public byte[] fromRowBytes(byte[][] palette, int width, byte bitDepth, byte[] rowBytes) {
            byte[] row = new byte[width * 4];
            int bytePos = 0;
            int bitPos = 0;
            for (int i = 0; i < width * 4; i += 4) {
                byte[] readPixel = new byte[componentsCount];
                if (bitDepth % Byte.SIZE == 0 && bitDepth >= Byte.SIZE) {
                    for (int j = 0; j < componentsCount; j++) {
                        readPixel[j] = rowBytes[bytePos + j * (bitDepth / Byte.SIZE)];
                    }
                    bytePos += componentsCount * (bitDepth / Byte.SIZE);
                } else if (Byte.SIZE % bitDepth == 0 && bitDepth < Byte.SIZE) {
                    for (int j = 0; j < componentsCount; j++) {
                        readPixel[j] = (byte) ((rowBytes[bytePos] >> bitPos) & (1 << bitDepth) - 1);
                        bitPos += bitDepth;
                        if (bitPos == Byte.SIZE) {
                            bitPos = 0;
                            bytePos++;
                        }
                    }
                }
                byte[] typedPixel = new byte[componentsCount];
                if (bitDepth == 16) {
                    for (int j = 0; j < typedPixel.length; j++) {
                        typedPixel[j] = readPixel[j * 2];
                    }
                } else if (this != INDEXED && bitDepth < Byte.SIZE) {
                    for (int j = 0; j < typedPixel.length; j++) {
                        typedPixel[j] = (byte) ((readPixel[j] & 0xFF) << (Byte.SIZE - bitDepth));
                    }
                } else {
                    typedPixel = readPixel;
                }
                byte[] pixel = fromType(palette, typedPixel);
                System.arraycopy(pixel, 0, row, i, 4);
            }
            return row;
        }
    }

    private enum CompressionMethod {
        DEFLATE((byte) 0);

        public final byte id;

        CompressionMethod(byte id) {
            this.id = id;
        }

        public static CompressionMethod getById(byte id) throws IOException {
            for (CompressionMethod compressionMethod : values()) {
                if (compressionMethod.id == id) {
                    return compressionMethod;
                }
            }
            throw new IOException("Invalid compression method");
        }
    }

    private enum FilterMethod {
        ADAPTIVE((byte) 0);

        public final byte id;

        FilterMethod(byte id) {
            this.id = id;
        }

        public static FilterMethod getById(byte id) throws IOException {
            for (FilterMethod filterMethod : values()) {
                if (filterMethod.id == id) {
                    return filterMethod;
                }
            }
            throw new IOException("Invalid filter method");
        }
    }

    private enum InterlaceMethod {
        NONE((byte) 0) {
            @Override
            public void encode(ByteBuffer image,
                               int width,
                               int height,
                               PassEncodeFunction passEncodeFunction) throws IOException {
                passEncodeFunction.encode(image, width, height);
            }

            @Override
            public ByteBuffer decode(int width,
                                     int height,
                                     PassDecodeFunction passDecodeFunction) throws IOException {
                return passDecodeFunction.decode(width, height);
            }
        },
        ADAM7((byte) 1) {
            private final int PASS_COUNT = 7;
            private final int[] X_STARTS = {0, 4, 0, 2, 0, 1, 0};
            private final int[] Y_STARTS = {0, 0, 4, 0, 2, 0, 1};
            private final int[] X_INCREMENTS = {8, 8, 4, 4, 2, 2, 1};
            private final int[] Y_INCREMENTS = {8, 8, 8, 4, 4, 2, 2};

            @SuppressWarnings("Duplicates")
            @Override
            public void encode(ByteBuffer image,
                               int width,
                               int height,
                               PassEncodeFunction passEncodeFunction) throws IOException {
                for (int i = 0; i < PASS_COUNT; i++) {
                    int passWidth = (width - 1 - X_STARTS[i]) / X_INCREMENTS[i] + 1;
                    int passHeight = (height - 1 - Y_STARTS[i]) / Y_INCREMENTS[i] + 1;
                    ByteBuffer passImage = ByteBuffer.allocateDirect(passWidth * passHeight * 4);
                    for (int y = Y_STARTS[i]; y < height; y += Y_INCREMENTS[i]) {
                        for (int x = X_STARTS[i]; x < width; x += X_INCREMENTS[i]) {
                            image.position((y * width + x) * 4);
                            passImage.put(image.get());
                            passImage.put(image.get());
                            passImage.put(image.get());
                            passImage.put(image.get());
                        }
                    }
                    passEncodeFunction.encode(passImage, passWidth, passHeight);
                }
            }

            @SuppressWarnings("Duplicates")
            @Override
            public ByteBuffer decode(int width,
                                     int height,
                                     PassDecodeFunction passDecodeFunction) throws IOException {
                ByteBuffer image = ByteBuffer.allocateDirect(width * height * 4);
                for (int i = 0; i < PASS_COUNT; i++) {
                    int passWidth = (width - 1 - X_STARTS[i]) / X_INCREMENTS[i] + 1;
                    int passHeight = (height - 1 - Y_STARTS[i]) / Y_INCREMENTS[i] + 1;
                    ByteBuffer passImage = passDecodeFunction.decode(passWidth, passHeight);
                    passImage.rewind();
                    for (int y = Y_STARTS[i]; y < height; y += Y_INCREMENTS[i]) {
                        for (int x = X_STARTS[i]; x < width; x += X_INCREMENTS[i]) {
                            image.position((y * width + x) * 4);
                            image.put(passImage.get());
                            image.put(passImage.get());
                            image.put(passImage.get());
                            image.put(passImage.get());
                        }
                    }
                }
                return image;
            }
        };

        public final byte id;

        InterlaceMethod(byte id) {
            this.id = id;
        }

        public static InterlaceMethod getById(byte id) throws IOException {
            for (InterlaceMethod interlaceMethod : values()) {
                if (interlaceMethod.id == id) {
                    return interlaceMethod;
                }
            }
            throw new IOException("Invalid interlace method");
        }

        public abstract void encode(ByteBuffer image,
                                    int width,
                                    int height,
                                    PassEncodeFunction passEncodeFunction) throws IOException;

        public abstract ByteBuffer decode(int width,
                                          int height,
                                          PassDecodeFunction passDecodeFunction) throws IOException;

        @FunctionalInterface
        public interface PassEncodeFunction {
            void encode(ByteBuffer passImage, int passWidth, int passHeight) throws IOException;
        }

        @FunctionalInterface
        public interface PassDecodeFunction {
            ByteBuffer decode(int passWidth, int passHeight) throws IOException;
        }
    }

    private enum FilterType {
        NONE((byte) 0) {
            @Override
            public int func(int a, int b, int c) {
                return 0;
            }
        },
        SUB((byte) 1) {
            @Override
            public int func(int a, int b, int c) {
                return a;
            }
        },
        UP((byte) 2) {
            @Override
            public int func(int a, int b, int c) {
                return b;
            }
        },
        AVERAGE((byte) 3) {
            @Override
            public int func(int a, int b, int c) {
                return (a + b) / 2;
            }
        },
        PAETH((byte) 4) {
            @Override
            public int func(int a, int b, int c) {
                int pa = Math.abs(b - c);
                int pb = Math.abs(a - c);
                int pc = Math.abs(a + b - c * 2);
                return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
            }
        };

        public final byte id;

        FilterType(byte id) {
            this.id = id;
        }

        public static FilterType getById(byte id) throws IOException {
            for (FilterType filterType : values()) {
                if (filterType.id == id) {
                    return filterType;
                }
            }
            throw new IOException("Invalid filter type");
        }

        public abstract int func(int a, int b, int c);

        private byte filter(byte originalX, byte originalA, byte originalB, byte originalC) {
            return (byte) ((originalX & 0xFF) - (func(
                (originalA & 0xFF),
                (originalB & 0xFF),
                (originalC & 0xFF)
            ) & 0xFF));
        }

        private byte reconstruct(byte filteredX, byte reconstructedA, byte reconstructedB, byte reconstructedC) {
            return (byte) ((filteredX & 0xFF) + (func(
                (reconstructedA & 0xFF),
                (reconstructedB & 0xFF),
                (reconstructedC & 0xFF)
            ) & 0xFF));
        }

        public byte[] filter(byte[] rowBytes, byte[] prevRowBytes, int pixelBytesCount) {
            byte[] filteredRowBytes = new byte[rowBytes.length];
            for (int i = 0; i < rowBytes.length; i++) {
                filteredRowBytes[i] = filter(
                    rowBytes[i],
                    i >= pixelBytesCount ? rowBytes[i - pixelBytesCount] : 0,
                    prevRowBytes != null ? prevRowBytes[i] : 0,
                    prevRowBytes != null && i >= pixelBytesCount ? prevRowBytes[i - pixelBytesCount] : 0
                );
            }
            return filteredRowBytes;
        }

        public byte[] reconstruct(byte[] row, byte[] reconstructedPrevRow, int pixelBytesCount) {
            byte[] reconstructedRow = new byte[row.length];
            for (int i = 0; i < row.length; i++) {
                reconstructedRow[i] = reconstruct(
                    row[i],
                    i >= pixelBytesCount ? reconstructedRow[i - pixelBytesCount] : 0,
                    reconstructedPrevRow != null ? reconstructedPrevRow[i] : 0,
                    reconstructedPrevRow != null && i >= pixelBytesCount ? reconstructedPrevRow[i - pixelBytesCount] : 0
                );
            }
            return reconstructedRow;
        }
    }

    private static class EncodePalette implements Iterable<byte[]> {
        private final int[] colors = new int[256];
        private final byte[] indexes = new byte[256];
        private byte[][] sortedColors;
        @SuppressWarnings("WeakerAccess")
        public int size = 0;
        @SuppressWarnings("WeakerAccess")
        public int alphaSize = 0;
        @SuppressWarnings("WeakerAccess")
        public boolean overflow = false;

        private static int colorToInt(byte[] color) {
            return (color[0] & 0xFF) << 24 | (color[1] & 0xFF) << 16 | (color[2] & 0xFF) << 8 | (color[3] & 0xFF);
        }

        private static byte[] intToColor(int color) {
            return new byte[] {(byte) (color >> 24), (byte) (color >> 16), (byte) (color >> 8), (byte) color};
        }

        @SuppressWarnings("WeakerAccess")
        public void addColor(byte[] color) {
            if (size < 256) {
                int intColor = colorToInt(color);
                boolean contains = false;
                for (int i = 0; i < size; i++) {
                    if (colors[i] == intColor) {
                        contains = true;
                        break;
                    }
                }
                if (!contains) {
                    colors[size++] = intColor;
                }
            } else {
                overflow = true;
            }
        }

        @SuppressWarnings("WeakerAccess")
        public void sort() {
            Arrays.sort(colors, 0, size);
            sortedColors = new byte[size][];
            int i = 0;
            for (int j = 0; j < size; j++) {
                if ((byte) colors[j] != (byte) 255) {
                    indexes[j] = (byte) i;
                    sortedColors[i++] = intToColor(colors[j]);
                }
            }
            alphaSize = i;
            for (int j = 0; j < size; j++) {
                if ((byte) colors[j] == (byte) 255) {
                    indexes[j] = (byte) i;
                    sortedColors[i++] = intToColor(colors[j]);
                }
            }
        }

        @SuppressWarnings("WeakerAccess")
        public byte getIndex(byte[] color) {
            return indexes[Arrays.binarySearch(colors, 0, size, colorToInt(color))];
        }

        @Override
        public Iterator<byte[]> iterator() {
            return Arrays.stream(sortedColors).iterator();
        }
    }
}
