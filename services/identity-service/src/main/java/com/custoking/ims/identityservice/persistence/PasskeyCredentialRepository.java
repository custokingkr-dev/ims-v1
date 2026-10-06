package com.custoking.ims.identityservice.persistence;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.nio.ByteBuffer;
import java.util.*;

@Repository
public class PasskeyCredentialRepository implements CredentialRepository {
    private final JdbcClient jdbc;
    public PasskeyCredentialRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public static ByteArray handle(long id) { return new ByteArray(ByteBuffer.allocate(Long.BYTES).putLong(id).array()); }
    public static long id(ByteArray handle) {
        if (handle.getBytes().length != Long.BYTES) throw new IllegalArgumentException("Invalid user handle");
        return ByteBuffer.wrap(handle.getBytes()).getLong();
    }
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        return new HashSet<>(jdbc.sql("SELECT c.credential_id FROM identity.passkey_credentials c JOIN identity.app_users u ON u.id=c.user_id WHERE lower(u.email)=lower(:username)")
                .param("username",username).query((rs,n)->PublicKeyCredentialDescriptor.builder().id(decode(rs.getString(1))).build()).list());
    }
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return jdbc.sql("SELECT id FROM identity.app_users WHERE lower(email)=lower(:username)").param("username",username).query(Long.class).optional().map(PasskeyCredentialRepository::handle);
    }
    public Optional<String> getUsernameForUserHandle(ByteArray handle) {
        return jdbc.sql("SELECT email FROM identity.app_users WHERE id=:id").param("id",id(handle)).query(String.class).optional();
    }
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        return credentials(credentialId).stream().filter(c->c.getUserHandle().equals(userHandle)).findFirst();
    }
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) { return new HashSet<>(credentials(credentialId)); }
    private List<RegisteredCredential> credentials(ByteArray credentialId) {
        return jdbc.sql("SELECT user_id,public_key_cose,signature_count FROM identity.passkey_credentials WHERE credential_id=:id")
                .param("id",credentialId.getBase64Url()).query((rs,n)->RegisteredCredential.builder().credentialId(credentialId)
                .userHandle(handle(rs.getLong(1))).publicKeyCose(decode(rs.getString(2))).signatureCount(rs.getLong(3)).build()).list();
    }
    private static ByteArray decode(String value) {
        try { return ByteArray.fromBase64Url(value); } catch(Exception ex) { throw new IllegalStateException("Invalid stored credential",ex); }
    }
}
