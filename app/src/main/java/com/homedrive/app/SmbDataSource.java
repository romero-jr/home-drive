package com.homedrive.app;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;

import com.hierynomus.smbj.share.File;

import java.io.IOException;

/**
 * Lets ExoPlayer stream a file straight from the SMB share, with seeking.
 * Reads are buffered: extractors like Matroska ask for a few bytes at a time,
 * and one SMB round trip per tiny read made mkv files stall while loading.
 */
@UnstableApi
final class SmbDataSource extends BaseDataSource {
    private final Context context;
    @Nullable private Uri uri;
    @Nullable private File file;
    private long position;
    private long bytesRemaining;
    private byte[] buffer = new byte[0];
    private int bufferOffset;
    private int bufferLength;

    SmbDataSource(Context context) {
        super(/* isNetwork= */ true);
        this.context = context.getApplicationContext();
    }

    static Uri uriFor(String path) {
        return new Uri.Builder().scheme("smb").authority("drive").path(path).build();
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        uri = dataSpec.uri;
        transferInitializing(dataSpec);
        String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        try {
            file = Smb.open(context, path);
        } catch (Exception e) {
            throw new IOException("Couldn't open " + path, e);
        }
        long size = file.getFileInformation().getStandardInformation().getEndOfFile();
        if (dataSpec.position > size) {
            throw new DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
        }
        position = dataSpec.position;
        bufferOffset = 0;
        bufferLength = 0;
        if (buffer.length == 0) buffer = new byte[Math.min(Smb.maxReadSize(), 1024 * 1024)];
        bytesRemaining = dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : size - position;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0 || file == null) return C.RESULT_END_OF_INPUT;
        if (bufferOffset == bufferLength) {
            int toFill = (int) Math.min(buffer.length, bytesRemaining);
            int filled;
            try {
                filled = file.read(buffer, position, 0, toFill);
            } catch (Exception e) {
                throw new IOException(e);
            }
            if (filled <= 0) return C.RESULT_END_OF_INPUT;
            bufferOffset = 0;
            bufferLength = filled;
        }
        int read = (int) Math.min(Math.min(length, bufferLength - bufferOffset), bytesRemaining);
        System.arraycopy(buffer, bufferOffset, target, offset, read);
        bufferOffset += read;
        position += read;
        bytesRemaining -= read;
        bytesTransferred(read);
        return read;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        uri = null;
        if (file != null) {
            try {
                file.close();
            } catch (Exception ignored) {
            }
            file = null;
            transferEnded();
        }
    }
}
