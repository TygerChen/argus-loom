package com.argus.rag.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 模型 API Key 的静态加密（AES-256-GCM）。
 * <p>密钥从 JWT 签名密钥经 SHA-256 派生（同源复用，避免再引入一个密钥配置项）；
 * 每次加密使用随机 IV，密文格式 = base64(iv(12B) + ciphertext)。</p>
 * <p>威胁模型：防"数据库落库泄露直接读出明文密钥"；不防"应用层被完全攻破"
 * （应用可解密是设计使然）。密钥轮换 = 重发用户配置。</p>
 */
@Component
public class ModelConfigCrypto {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public ModelConfigCrypto(@Value("${argus.rag.auth.jwt-secret}") String jwtSecret) {
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256")
                    .digest(jwtSecret.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(derived, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("模型配置加密密钥初始化失败", e);
        }
    }

    /** 加密明文密钥，返回 base64(iv + ciphertext)。 */
    public String encrypt(String plainApiKey) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plainApiKey.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    /** 解密 base64(iv + ciphertext)，格式非法或认证失败抛 IllegalArgumentException。 */
    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length <= GCM_IV_BYTES) {
                throw new IllegalArgumentException("密文长度非法");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, all, 0, GCM_IV_BYTES));
            byte[] plain = cipher.doFinal(all, GCM_IV_BYTES, all.length - GCM_IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("API Key 解密失败（密钥是否轮换过？）", e);
        }
    }

    /** 掩码展示：sk-abc…xyz，不足 8 位一律 ****。 */
    public static String mask(String plainApiKey) {
        if (plainApiKey == null || plainApiKey.length() < 8) {
            return "****";
        }
        return plainApiKey.substring(0, 6) + "…" + plainApiKey.substring(plainApiKey.length() - 4);
    }
}
