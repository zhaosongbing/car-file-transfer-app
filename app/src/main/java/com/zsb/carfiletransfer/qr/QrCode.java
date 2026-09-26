package com.zsb.carfiletransfer.qr;

/**
 * Minimal, dependency-free QR Code encoder (byte mode, error-correction level M).
 * Produces the module matrix for versions 1..10, which comfortably covers
 * Wi-Fi credentials and short HTTP URLs.
 */
public final class QrCode {

    private static final int PENALTY_N1 = 3;
    private static final int PENALTY_N2 = 3;
    private static final int PENALTY_N4 = 10;

    /**
     * {dataCodewords, ecCodewordsPerBlock, g1Blocks, g1DataLen, g2Blocks, g2DataLen}
     * per version, for error-correction level M.
     * Total codewords = dataCodewords + ecCodewordsPerBlock * (g1Blocks + g2Blocks).
     */
    private static final int[][] SPEC_M = {
            {16, 10, 1, 16, 0, 0},      // v1  -> 26 total
            {28, 16, 1, 28, 0, 0},      // v2  -> 44 total
            {44, 26, 1, 44, 0, 0},      // v3  -> 70 total
            {64, 18, 2, 32, 0, 0},      // v4  -> 100 total
            {86, 24, 2, 43, 0, 0},      // v5  -> 134 total
            {108, 16, 4, 27, 0, 0},     // v6  -> 172 total
            {124, 18, 4, 31, 0, 0},     // v7  -> 196 total
            {154, 22, 2, 38, 2, 39},    // v8  -> 242 total
            {182, 22, 3, 36, 2, 37},    // v9  -> 292 total
            {216, 26, 4, 43, 1, 44},    // v10 -> 346 total
    };

    /** Remainder bits per version (index 0 = version 1). */
    private static final int[] REMAINDER = {0, 7, 7, 7, 7, 7, 0, 0, 0, 0};

    private final int version;
    private final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    private QrCode(int version) {
        this.version = version;
        this.size = version * 4 + 17;
        this.modules = new boolean[size][size];
        this.isFunction = new boolean[size][size];
    }

    /**
     * Encode text into a square module matrix (true = dark).
     *
     * @throws IllegalArgumentException if the payload is too long for v10-M
     */
    public static boolean[][] encode(String text) {
        return new QrCode(chooseVersion(text)).build(text);
    }

    /** Size (modules per side) needed for the given text. */
    public static int sizeFor(String text) {
        return chooseVersion(text) * 4 + 17;
    }

    private static int chooseVersion(String text) {
        byte[] data = utf8(text);
        for (int v = 1; v <= SPEC_M.length; v++) {
            int[] spec = SPEC_M[v - 1];
            int capacityBits = spec[0] * 8;
            int overhead = 4 + (v < 10 ? 8 : 16);
            if (data.length * 8 + overhead <= capacityBits) return v;
        }
        throw new IllegalArgumentException("内容过长，无法生成二维码");
    }

    // ---------------- build pipeline ----------------

    private boolean[][] build(String text) {
        drawFunctionPatterns();
        byte[] codewords = makeCodewords(utf8(text));
        drawCodewords(codewords);
        int mask = chooseBestMask();
        applyMask(mask);
        drawFormatBits(mask);
        return modules;
    }

    private byte[] makeCodewords(byte[] data) {
        int[] spec = SPEC_M[version - 1];
        int dataLen = spec[0];
        int ecLen = spec[1];
        int g1 = spec[2];
        int g1Data = spec[3];
        int g2 = spec[4];
        int g2Data = spec[5];

        // bit stream: mode + length + payload + terminator + padding
        BitBuffer bits = new BitBuffer();
        bits.appendBits(0x4, 4);
        bits.appendBits(data.length, version < 10 ? 8 : 16);
        for (int i = 0; i < data.length; i++) bits.appendBits(data[i] & 0xFF, 8);

        int capacityBits = dataLen * 8;
        int terminator = Math.min(4, capacityBits - bits.size());
        bits.appendBits(0, terminator);
        bits.appendBits(0, (8 - bits.size() % 8) % 8);
        for (int pad = 0xEC; bits.size() < capacityBits; pad ^= 0xEC ^ 0x11) {
            bits.appendBits(pad, 8);
        }

        byte[] plain = bits.toBytes();

        // split into blocks
        int numBlocks = g1 + g2;
        byte[][] dataBlocks = new byte[numBlocks][];
        byte[][] ecBlocks = new byte[numBlocks][];
        int offset = 0;
        for (int i = 0; i < g1; i++) {
            dataBlocks[i] = slice(plain, offset, g1Data);
            offset += g1Data;
        }
        for (int i = 0; i < g2; i++) {
            dataBlocks[g1 + i] = slice(plain, offset, g2Data);
            offset += g2Data;
        }
        for (int i = 0; i < numBlocks; i++) {
            ecBlocks[i] = reedSolomon(dataBlocks[i], ecLen);
        }

        // interleave
        int maxData = Math.max(g1Data, g2Data);
        java.util.ArrayList<Byte> out = new java.util.ArrayList<Byte>();
        for (int i = 0; i < maxData; i++) {
            for (int b = 0; b < numBlocks; b++) {
                if (i < dataBlocks[b].length) out.add(Byte.valueOf(dataBlocks[b][i]));
            }
        }
        for (int i = 0; i < ecLen; i++) {
            for (int b = 0; b < numBlocks; b++) {
                out.add(Byte.valueOf(ecBlocks[b][i]));
            }
        }
        byte[] result = new byte[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i).byteValue();
        return result;
    }

    private static byte[] slice(byte[] src, int offset, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, offset, out, 0, len);
        return out;
    }

    // ---------------- function patterns ----------------

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            setFunctionModule(6, i, i % 2 == 0);
            setFunctionModule(i, 6, i % 2 == 0);
        }
        drawFinderPattern(3, 3);
        drawFinderPattern(size - 4, 3);
        drawFinderPattern(3, size - 4);

        int[] align = alignmentPositions();
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0))) {
                    drawAlignmentPattern(align[i], align[j]);
                }
            }
        }
        drawFormatBits(0);
        drawVersion();
    }

    private void drawFinderPattern(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx;
                int yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) {
                    setFunctionModule(xx, yy, dist != 2 && dist != 4);
                }
            }
        }
    }

    private void drawAlignmentPattern(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunctionModule(x + dx, y + dy,
                        Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int numAlign = version / 7 + 2;
        int step = (version == 32) ? 26
                : (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        // fill from the end so the array comes out in ascending order
        for (int i = numAlign - 1, pos = size - 7; i >= 1; i--, pos -= step) {
            result[i] = pos;
        }
        return result;
    }

    private void drawFormatBits(int mask) {
        int data = 0x00 << 3 | mask;               // EC level M -> format bits 00
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = ((data << 10) | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) setFunctionModule(8, i, getBit(bits, i));
        setFunctionModule(8, 7, getBit(bits, 6));
        setFunctionModule(8, 8, getBit(bits, 7));
        setFunctionModule(7, 8, getBit(bits, 8));
        for (int i = 9; i < 15; i++) setFunctionModule(14 - i, 8, getBit(bits, i));

        for (int i = 0; i < 8; i++) setFunctionModule(size - 1 - i, 8, getBit(bits, i));
        for (int i = 8; i < 15; i++) setFunctionModule(8, size - 15 + i, getBit(bits, i));
        setFunctionModule(8, size - 8, true);
    }

    private void drawVersion() {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        int bits = (version << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean bit = getBit(bits, i);
            int a = size - 11 + i % 3;
            int b = i / 3;
            setFunctionModule(a, b, bit);
            setFunctionModule(b, a, bit);
        }
    }

    private void setFunctionModule(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    // ---------------- data placement ----------------

    private void drawCodewords(byte[] data) {
        int i = 0;
        int totalBits = data.length * 8 + REMAINDER[version - 1];
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < totalBits) {
                        boolean dark = false;
                        if (i < data.length * 8) {
                            dark = getBit(data[i >>> 3], 7 - (i & 7));
                        }
                        modules[y][x] = dark;
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (isFunction[y][x]) continue;
                boolean invert;
                switch (mask) {
                    case 0:
                        invert = (x + y) % 2 == 0;
                        break;
                    case 1:
                        invert = y % 2 == 0;
                        break;
                    case 2:
                        invert = x % 3 == 0;
                        break;
                    case 3:
                        invert = (x + y) % 3 == 0;
                        break;
                    case 4:
                        invert = (x / 3 + y / 2) % 2 == 0;
                        break;
                    case 5:
                        invert = x * y % 2 + x * y % 3 == 0;
                        break;
                    case 6:
                        invert = (x * y % 2 + x * y % 3) % 2 == 0;
                        break;
                    case 7:
                    default:
                        invert = ((x + y) % 2 + x * y % 3) % 2 == 0;
                        break;
                }
                if (invert) modules[y][x] = !modules[y][x];
            }
        }
    }

    private int chooseBestMask() {
        int best = 0;
        long min = Long.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            applyMask(i);
            drawFormatBits(i);
            long score = penalty();
            if (score < min) {
                min = score;
                best = i;
            }
            applyMask(i);   // XOR undoes the mask
        }
        return best;
    }

    private long penalty() {
        long result = 0;

        // rule 1: runs of five or more same-colour modules
        for (int y = 0; y < size; y++) {
            boolean color = false;
            int run = 0;
            for (int x = 0; x < size; x++) {
                if (modules[y][x] == color) {
                    run++;
                    if (run == 5) result += PENALTY_N1;
                    else if (run > 5) result++;
                } else {
                    color = modules[y][x];
                    run = 1;
                }
            }
        }
        for (int x = 0; x < size; x++) {
            boolean color = false;
            int run = 0;
            for (int y = 0; y < size; y++) {
                if (modules[y][x] == color) {
                    run++;
                    if (run == 5) result += PENALTY_N1;
                    else if (run > 5) result++;
                } else {
                    color = modules[y][x];
                    run = 1;
                }
            }
        }

        // rule 2: 2x2 blocks of the same colour
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x]
                        && c == modules[y + 1][x + 1]) {
                    result += PENALTY_N2;
                }
            }
        }

        // rule 4: deviation from a 50% dark ratio
        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) dark++;
            }
        }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        result += (long) k * PENALTY_N4;
        return result;
    }

    // ---------------- helpers ----------------

    private static boolean getBit(int value, int index) {
        return ((value >>> index) & 1) != 0;
    }

    private static byte[] utf8(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (Exception e) {
            return text.getBytes();
        }
    }

    /** Reed-Solomon remainder over GF(2^8) with primitive polynomial 0x11D. */
    private static byte[] reedSolomon(byte[] data, int ecLen) {
        int[] gen = new int[ecLen + 1];
        gen[0] = 1;
        int degree = 0;
        for (int i = 0; i < ecLen; i++) {
            // multiply by (x - 2^i)
            int root = gexp(i);
            for (int j = degree + 1; j > 0; j--) {
                gen[j] = gen[j - 1] ^ gmul(gen[j], root);
            }
            gen[0] = gmul(gen[0], root);
            degree++;
        }

        byte[] result = new byte[ecLen];
        for (int i = 0; i < data.length; i++) {
            int factor = (data[i] & 0xFF) ^ (result[0] & 0xFF);
            System.arraycopy(result, 1, result, 0, ecLen - 1);
            result[ecLen - 1] = 0;
            // gen[] is stored low-to-high order; the division consumes every
            // coefficient except the leading (highest-order) one, high to low.
            for (int j = 0; j < ecLen; j++) {
                result[j] = (byte) ((result[j] & 0xFF) ^ gmul(gen[ecLen - 1 - j], factor));
            }
        }
        return result;
    }

    private static int gexp(int n) {
        int x = 1;
        for (int i = 0; i < n; i++) x = gmul(x, 2);
        return x;
    }

    private static int gmul(int a, int b) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((b >>> i) & 1) * a;
        }
        return z & 0xFF;
    }

    /** CLI self-test: prints the module matrix as 0/1 rows (used by the build tests). */
    public static void main(String[] args) {
        if (args.length > 0 && "--dump".equals(args[0]) && args.length > 1) {
            byte[] payload = hexToBytes(args[1]);
            int v = chooseVersion(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            byte[] cw = new QrCode(v).makeCodewords(payload);
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < cw.length; i++) {
                String b = Integer.toHexString(cw[i] & 0xFF);
                if (b.length() == 1) h.append('0');
                h.append(b);
            }
            System.out.println("version=" + v + " codewords=" + h);
            return;
        }
        String text = "http://192.168.43.1:8899";
        if (args.length > 0) {
            if ("--hex".equals(args[0]) && args.length > 1) {
                byte[] raw = new byte[args[1].length() / 2];
                for (int i = 0; i < raw.length; i++) {
                    raw[i] = (byte) Integer.parseInt(args[1].substring(i * 2, i * 2 + 2), 16);
                }
                try {
                    text = new String(raw, "UTF-8");
                } catch (Exception ignored) {
                    text = new String(raw);
                }
            } else {
                text = args[0];
            }
        }
        boolean[][] m = encode(text);
        StringBuilder sb = new StringBuilder();
        sb.append(m.length).append('\n');
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m.length; x++) {
                sb.append(m[y][x] ? '1' : '0');
            }
            sb.append('\n');
        }
        System.out.print(sb);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Simple bit accumulator. */
    private static final class BitBuffer {
        private final java.util.ArrayList<Integer> bits = new java.util.ArrayList<Integer>();

        void appendBits(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                bits.add(Integer.valueOf((value >>> i) & 1));
            }
        }

        int size() {
            return bits.size();
        }

        byte[] toBytes() {
            byte[] out = new byte[(bits.size() + 7) / 8];
            for (int i = 0; i < bits.size(); i++) {
                out[i >>> 3] |= (byte) (bits.get(i).intValue() << (7 - (i & 7)));
            }
            return out;
        }
    }
}
