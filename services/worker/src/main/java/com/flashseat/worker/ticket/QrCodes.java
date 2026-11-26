package com.flashseat.worker.ticket;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public final class QrCodes {

    private static final int SIZE_PIXELS = 300;

    private QrCodes() {
    }

    public static byte[] png(String text) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, SIZE_PIXELS, SIZE_PIXELS);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", png);
            return png.toByteArray();
        } catch (WriterException e) {
            throw new IllegalArgumentException("Text cannot be encoded as a QR code", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
