package moe.ouom.archive.netease;

// Adapted from NeriPlayer NeteaseCrypto.kt. Copyright (C) 2025 NeriPlayer developers.
// SPDX-License-Identifier: GPL-3.0-or-later
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.math.BigInteger;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

public final class NeteaseCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";
    private static byte[] aes(String text, String key, boolean cbc) throws Exception {
        var cipher = Cipher.getInstance(cbc ? "AES/CBC/PKCS5Padding" : "AES/ECB/PKCS5Padding");
        var secret = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
        if (cbc) cipher.init(Cipher.ENCRYPT_MODE, secret, new IvParameterSpec("0102030405060708".getBytes(StandardCharsets.UTF_8)));
        else cipher.init(Cipher.ENCRYPT_MODE, secret);
        return cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }
    public static Map<String,String> weapi(String json) throws Exception {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        var secret = new StringBuilder();
        for (int i=0;i<16;i++) secret.append(chars.charAt(RANDOM.nextInt(chars.length())));
        String first = Base64.getEncoder().encodeToString(aes(json,"0CoJUm6Qyw8W8jud",true));
        String params = Base64.getEncoder().encodeToString(aes(first,secret.toString(),true));
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
        String rsa = new BigInteger(1, secret.reverse().toString().getBytes(StandardCharsets.UTF_8)).modPow(key.getPublicExponent(),key.getModulus()).toString(16);
        return Map.of("params",params,"encSecKey","0".repeat(256-rsa.length())+rsa);
    }
    public static Map<String,String> eapi(String path, String json) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(("nobody"+path+"use"+json+"md5forencrypt").getBytes(StandardCharsets.UTF_8)));
        return Map.of("params",HexFormat.of().withUpperCase().formatHex(aes(path+"-36cd479b6b5-"+json+"-36cd479b6b5-"+digest,"e82ckenh8dichen8",false)));
    }
}
