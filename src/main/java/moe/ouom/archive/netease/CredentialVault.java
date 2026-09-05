package moe.ouom.archive.netease;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

@Component
public class CredentialVault {
    private final Path root;
    private final byte[] key;
    public CredentialVault(@Value("${archive.data}") String data) throws Exception {
        root=Path.of(data); Files.createDirectories(root);
        Path keyPath=root.resolve("credentials.key");
        if (!Files.exists(keyPath)) {
            if (Files.exists(root.resolve("credentials.enc"))) throw new IllegalStateException("凭据密钥丢失，请恢复 credentials.key");
            byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
            Files.write(keyPath,bytes,StandardOpenOption.CREATE_NEW);
            try { Files.setPosixFilePermissions(keyPath,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) {}
        }
        key=Files.readAllBytes(keyPath);
        if(key.length!=32) throw new IllegalStateException("凭据密钥格式无效");
    }
    public synchronized String load() throws Exception {
        Path file=root.resolve("credentials.enc"); if(!Files.exists(file)) return "";
        byte[] bytes=Files.readAllBytes(file);
        if(bytes.length<28) throw new IllegalStateException("凭据文件损坏");
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));
        return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);
    }
    public synchronized void save(String value) throws Exception {
        byte[] nonce=new byte[12]; new SecureRandom().nextBytes(nonce);
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] payload=new byte[nonce.length+encrypted.length];
        System.arraycopy(nonce,0,payload,0,12); System.arraycopy(encrypted,0,payload,12,encrypted.length);
        Path temp=root.resolve("credentials.enc.tmp"); Files.write(temp,payload);
        Files.move(temp,root.resolve("credentials.enc"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}
