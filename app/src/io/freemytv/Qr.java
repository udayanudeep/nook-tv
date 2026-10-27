package io.freemytv;

import android.graphics.Bitmap;

import java.nio.charset.StandardCharsets;

/**
 * A QR code for a short link (PhotosActivity's address), with no library: version 4 (33×33), error correction M,
 * byte mode, mask 0. Up to 62 bytes. Follows ISO/IEC 18004 the way Nayuki's reference encoder does.
 */
final class Qr {
    private Qr() {}

    private static final int SIZE = 33, DATA_CODEWORDS = 64, BLOCKS = 2, ECC_PER_BLOCK = 18;
    static final int MAX_BYTES = 62;

    private static boolean[][] modules, function;

    /** The code as black-on-white pixels, `scale` per module, with the 4-module quiet zone. */
    static synchronized Bitmap bitmap(String text, int scale) {
        boolean[][] m = encode(text);
        int n = (SIZE + 8) * scale;
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int mx = x / scale - 4, my = y / scale - 4;
                boolean dark = mx >= 0 && my >= 0 && mx < SIZE && my < SIZE && m[my][mx];
                px[y * n + x] = dark ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    static synchronized boolean[][] encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("too long for the QR code");
        modules = new boolean[SIZE][SIZE];
        function = new boolean[SIZE][SIZE];
        drawFunctionPatterns();
        drawCodewords(addEcc(dataCodewords(bytes)));
        applyMask();
        drawFormatBits();
        return modules;
    }

    // MARK: - Data

    private static byte[] dataCodewords(byte[] bytes) {
        int capacity = DATA_CODEWORDS * 8;
        boolean[] bits = new boolean[capacity];
        int n = 0;
        n = append(bits, n, 0b0100, 4);           // byte mode
        n = append(bits, n, bytes.length, 8);     // count (8 bits up to version 9)
        for (byte b : bytes) n = append(bits, n, b & 0xFF, 8);
        n += Math.min(4, capacity - n);           // terminator
        n = (n + 7) / 8 * 8;
        byte[] out = new byte[DATA_CODEWORDS];
        for (int i = 0; i < n; i++) if (bits[i]) out[i >>> 3] |= (byte) (0x80 >>> (i & 7));
        for (int i = n / 8, pad = 0xEC; i < DATA_CODEWORDS; i++, pad ^= 0xEC ^ 0x11) out[i] = (byte) pad;
        return out;
    }

    private static int append(boolean[] bits, int at, int value, int length) {
        for (int i = length - 1; i >= 0; i--) bits[at++] = ((value >>> i) & 1) != 0;
        return at;
    }

    /** Two blocks of 32 data codewords, each with 18 of Reed-Solomon, interleaved. */
    private static byte[] addEcc(byte[] data) {
        int per = DATA_CODEWORDS / BLOCKS;
        byte[] divisor = divisor(ECC_PER_BLOCK);
        byte[][] blocks = new byte[BLOCKS][], ecc = new byte[BLOCKS][];
        for (int b = 0; b < BLOCKS; b++) {
            blocks[b] = java.util.Arrays.copyOfRange(data, b * per, (b + 1) * per);
            ecc[b] = remainder(blocks[b], divisor);
        }
        byte[] out = new byte[DATA_CODEWORDS + BLOCKS * ECC_PER_BLOCK];
        int k = 0;
        for (int i = 0; i < per; i++) for (int b = 0; b < BLOCKS; b++) out[k++] = blocks[b][i];
        for (int i = 0; i < ECC_PER_BLOCK; i++) for (int b = 0; b < BLOCKS; b++) out[k++] = ecc[b][i];
        return out;
    }

    private static byte[] divisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) multiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    private static byte[] remainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) multiply(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    // MARK: - Matrix

    private static void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private static void drawFunctionPatterns() {
        for (int i = 0; i < SIZE; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(SIZE - 4, 3);
        drawFinder(3, SIZE - 4);
        // Version 4 has one alignment pattern (the others would overlap the finders).
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) set(26 + dx, 26 + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
        drawFormatBits();   // reserves the format areas (written again after masking)
    }

    private static void drawFinder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy)), xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < SIZE && yy >= 0 && yy < SIZE) set(xx, yy, dist != 2 && dist != 4);
            }
        }
    }

    /** Level M (00) and mask 0, with its BCH code, in both copies; and the dark module. */
    private static void drawFormatBits() {
        int data = 0;   // (M = 00) << 3 | mask 0
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(SIZE - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, SIZE - 15 + i, bit(bits, i));
        set(8, SIZE - 8, true);
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    /** The zigzag: two columns at a time from the right, up then down, skipping the vertical timing line. */
    private static void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = SIZE - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < SIZE; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? SIZE - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3] & 0xFF, 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private static void applyMask() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) if (!function[y][x] && (x + y) % 2 == 0) modules[y][x] = !modules[y][x];
        }
    }
}
