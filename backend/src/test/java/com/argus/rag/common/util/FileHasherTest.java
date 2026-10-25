package com.argus.rag.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FileHasher} SHA-256 摘要契约（纯 JVM，确定性）。
 * 已知输入→64 位小写十六进制；空输入已知摘要；同输入幂等且与编码无关的确定性。
 */
@DisplayName("文件哈希（FileHasher）")
class FileHasherTest {

    @Test
    void sha256HexOfHelloMatchesKnownDigest() {
        byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
        String digest = FileHasher.sha256Hex(hello);
        assertEquals(64, digest.length());
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", digest);
    }

    @Test
    void sha256HexOfEmptyIsWellKnown() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                FileHasher.sha256Hex(new byte[0]));
    }

    @Test
    void deterministicAcrossCalls() {
        byte[] data = "argus-loom fingerprint 42".getBytes(StandardCharsets.UTF_8);
        assertEquals(FileHasher.sha256Hex(data), FileHasher.sha256Hex(data));
    }

    @Test
    void lowercaseHexOnly() {
        String digest = FileHasher.sha256Hex("ABC".getBytes(StandardCharsets.UTF_8));
        assertEquals(digest.toLowerCase(), digest);
    }

    @Test
    void differsAcrossInputs() {
        String a = FileHasher.sha256Hex("a".getBytes(StandardCharsets.UTF_8));
        String b = FileHasher.sha256Hex("b".getBytes(StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }
}