package com.homedrive.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.security.bc.BCSecurityProvider;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Shared SMB connection to the router's drive, reconnecting on demand. */
final class Smb {
    static final class Entry {
        String name;
        String path;      // relative to the share, '/' separated
        boolean directory;
        long size;
    }

    private static SMBClient client;
    private static Connection connection;
    private static DiskShare share;

    private Smb() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("connection", Context.MODE_PRIVATE);
    }

    static synchronized DiskShare share(Context context) throws IOException {
        if (share != null && share.isConnected()) return share;
        disconnect();
        SharedPreferences p = prefs(context);
        String user = p.getString("username", "");
        String password = p.getString("password", "");
        SmbConfig config = SmbConfig.builder()
                .withSecurityProvider(new BCSecurityProvider())
                .withTimeout(20, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .build();
        client = new SMBClient(config);
        connection = client.connect(p.getString("server", ""));
        AuthenticationContext auth = user.isEmpty()
                ? AuthenticationContext.guest()
                : new AuthenticationContext(user, password.toCharArray(), null);
        Session session = connection.authenticate(auth);
        share = (DiskShare) session.connectShare(p.getString("share", ""));
        return share;
    }

    static synchronized void disconnect() {
        try {
            if (client != null) client.close();
        } catch (Exception ignored) {
        }
        client = null;
        connection = null;
        share = null;
    }

    static int maxReadSize() {
        Connection c = connection;
        return c != null ? c.getNegotiatedProtocol().getMaxReadSize() : 64 * 1024;
    }

    static List<Entry> list(Context context, String path) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (FileIdBothDirectoryInformation info : share(context).list(toSmb(path))) {
            String name = info.getFileName();
            if (name.equals(".") || name.equals("..") || name.startsWith(".") || name.startsWith("$")
                    || name.equals("System Volume Information")) continue;
            long attrs = info.getFileAttributes();
            if ((attrs & FileAttributes.FILE_ATTRIBUTE_HIDDEN.getValue()) != 0) continue;
            Entry e = new Entry();
            e.name = name;
            e.path = path.isEmpty() ? name : path + "/" + name;
            e.directory = (attrs & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
            e.size = info.getEndOfFile();
            entries.add(e);
        }
        return entries;
    }

    static File open(Context context, String path) throws IOException {
        return share(context).openFile(toSmb(path), EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
    }

    private static String toSmb(String path) {
        return path.replace('/', '\\');
    }
}
