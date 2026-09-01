package com.keytrins.kucoingrid;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CredentialStore {
    static final class Credentials { final String key,secret,pass; Credentials(String k,String s,String p){key=k;secret=s;pass=p;} }
    private static final String PREF="kucoin_grid_credentials";
    private static final String ALIAS="com.keytrins.kucoingrid.api.v1";
    private final SharedPreferences prefs;
    CredentialStore(Context c){prefs=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}

    void save(String key,String secret,String pass)throws Exception{
        SecretKey k=getOrCreateKey();
        prefs.edit().putString("api_key",enc(k,key)).putString("api_secret",enc(k,secret)).putString("api_pass",enc(k,pass)).apply();
    }
    Credentials load(){
        try{
            String a=prefs.getString("api_key",null),b=prefs.getString("api_secret",null),c=prefs.getString("api_pass",null);
            if(a==null||b==null||c==null)return null;
            SecretKey k=getOrCreateKey();
            return new Credentials(dec(k,a),dec(k,b),dec(k,c));
        }catch(Exception e){clear();return null;}
    }
    void clear(){prefs.edit().clear().apply();}

    private static SecretKey getOrCreateKey()throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        java.security.Key existing=ks.getKey(ALIAS,null);
        if(existing instanceof SecretKey)return (SecretKey)existing;
        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return kg.generateKey();
    }
    private static String enc(SecretKey k,String text)throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,k);byte[] iv=c.getIV(),ct=c.doFinal(text.getBytes(StandardCharsets.UTF_8));byte[] out=new byte[1+iv.length+ct.length];out[0]=(byte)iv.length;System.arraycopy(iv,0,out,1,iv.length);System.arraycopy(ct,0,out,1+iv.length,ct.length);return Base64.encodeToString(out,Base64.NO_WRAP);
    }
    private static String dec(SecretKey k,String value)throws Exception{
        byte[] all=Base64.decode(value,Base64.NO_WRAP);int n=all[0]&255;if(n<12||n>32||all.length<=1+n)throw new IllegalArgumentException("bad credential blob");byte[] iv=new byte[n],ct=new byte[all.length-1-n];System.arraycopy(all,1,iv,0,n);System.arraycopy(all,1+n,ct,0,ct.length);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,k,new GCMParameterSpec(128,iv));return new String(c.doFinal(ct),StandardCharsets.UTF_8);
    }
}
