package com.haf1.racecontrol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/**
 * RFC 6455 WebSocket 帧的编解码。纯逻辑，无 Android 依赖 —— 因此可以被桌面单测覆盖。
 *
 * 两个最容易写错、且错了会静默失效的地方：
 *   1. 客户端发往服务端的帧**必须掩码**（MASK 位 = 1），服务端发来的帧**必须不掩码**。
 *   2. 长度有 7 位 / 16 位 / 64 位三种编码，边界是 125 和 65535。
 */
public class WsFrame {

    public static final int OP_CONT = 0x0;
    public static final int OP_TEXT = 0x1;
    public static final int OP_BIN = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    /** 单帧负载上限，防御性限制，避免被畸形帧撑爆内存。 */
    private static final int MAX_PAYLOAD = 4 * 1024 * 1024;

    private static final Random RND = new Random();

    public final boolean fin;
    public final int opcode;
    public final byte[] payload;

    public WsFrame(boolean fin, int opcode, byte[] payload) {
        this.fin = fin;
        this.opcode = opcode;
        this.payload = payload == null ? new byte[0] : payload;
    }

    // ------------------------------------------------------------------
    // 编码（客户端 -> 服务端）
    // ------------------------------------------------------------------

    public static byte[] encode(int opcode, byte[] payload) {
        byte[] mask = new byte[4];
        RND.nextBytes(mask);
        return encode(opcode, payload, mask);
    }

    /** 允许注入固定掩码，纯粹为了单测可复现。 */
    public static byte[] encode(int opcode, byte[] payload, byte[] mask) {
        byte[] body = payload == null ? new byte[0] : payload;
        ByteArrayOutputStream b = new ByteArrayOutputStream(body.length + 14);

        b.write(0x80 | (opcode & 0x0F));        // FIN=1

        int len = body.length;
        if (len < 126) {
            b.write(0x80 | len);                // MASK=1
        } else if (len <= 0xFFFF) {
            b.write(0x80 | 126);
            b.write((len >>> 8) & 0xFF);
            b.write(len & 0xFF);
        } else {
            b.write(0x80 | 127);
            long l = len;
            for (int i = 7; i >= 0; i--) {
                b.write((int) ((l >>> (8 * i)) & 0xFF));
            }
        }

        b.write(mask, 0, 4);
        for (int i = 0; i < len; i++) {
            b.write(body[i] ^ mask[i & 3]);
        }
        return b.toByteArray();
    }

    // ------------------------------------------------------------------
    // 解码（服务端 -> 客户端）
    // ------------------------------------------------------------------

    public static WsFrame read(InputStream in) throws IOException {
        int b0 = readByte(in);
        int b1 = readByte(in);

        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;

        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((long) readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByte(in);
            }
        }
        if (len < 0 || len > MAX_PAYLOAD) {
            throw new IOException("WebSocket 帧负载过大或非法: " + len);
        }

        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }

        byte[] payload = new byte[(int) len];
        readFully(in, payload);

        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }
        return new WsFrame(fin, opcode, payload);
    }


    /**
     * 读**一整条消息**，自动拼接分片帧。
     *
     * ★ 为什么必须做：F1 官方实时流的初始快照有几十 KB（一节的
     *   RaceControlMessages、TimingData 全在里面），服务端会拆成多个帧 ——
     *   首帧 opcode = TEXT/BIN 且 FIN=0，后续 opcode = CONT(0)，最后一帧 FIN=1。
     *   只调 read() 会把**半截 JSON** 交出去。这个坑在 Python 侧实测踩过：
     *   解析报错，而报错信息看起来像服务端发了坏数据。
     *
     * 控制帧（PING/PONG/CLOSE）可以插在分片中间，遇到就直接返回给调用方
     * （PING 要回 PONG，CLOSE 要断开），不参与拼接。
     */
    public static WsFrame readMessage(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int firstOp = -1;
        while (true) {
            WsFrame f = read(in);
            if (f.opcode == OP_CLOSE || f.opcode == OP_PING || f.opcode == OP_PONG) {
                return f;
            }
            if (f.opcode != OP_CONT) {
                firstOp = f.opcode;
            }
            buf.write(f.payload, 0, f.payload.length);
            if (f.fin) {
                return new WsFrame(true, firstOp < 0 ? OP_TEXT : firstOp, buf.toByteArray());
            }
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int v = in.read();
        if (v < 0) {
            throw new EOFException("连接已关闭");
        }
        return v & 0xFF;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new EOFException("连接在帧中途关闭");
            }
            off += n;
        }
    }
}
