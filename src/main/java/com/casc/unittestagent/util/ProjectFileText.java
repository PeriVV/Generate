package com.casc.unittestagent.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fresh disk bytes decoded with IDEA's encoding and normalized to the editor's LF text model. */
public final class ProjectFileText {
    private ProjectFileText() { }

    public static String read(Path path) throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        if (ApplicationManager.getApplication() != null) {
            var file = LocalFileSystem.getInstance().findFileByNioFile(path);
            if (file == null) file = LocalFileSystem.getInstance().findFileByNioFile(path.toRealPath());
            if (file == null && !ApplicationManager.getApplication().isReadAccessAllowed()) {
                file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path.toRealPath());
            }
            if (file != null) charset = ReadAction.compute(file::getCharset);
        }
        byte[] bytes = Files.readAllBytes(path);
        int skip = 0;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            charset = StandardCharsets.UTF_8; skip = 3;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            charset = StandardCharsets.UTF_16LE; skip = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            charset = StandardCharsets.UTF_16BE; skip = 2;
        }
        var decoded = charset.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, skip, bytes.length - skip));
        return StringUtil.convertLineSeparators(decoded.toString());
    }

    public static String diskHash(Path path) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[32768]; int size;
                while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
