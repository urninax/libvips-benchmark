package com.journi.bench.s3;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridges libvips' VCustomTarget WriteCallback to an S3 upload with unknown
 * total size (true streaming encode has no length until it finishes).
 *
 * CRITICAL CORRECTNESS DETAIL: resized photobook outputs (a few hundred KB to
 * a few MB) will almost never cross S3's 5 MiB multipart minimum part size.
 * If every job unconditionally paid CreateMultipartUpload + UploadPart +
 * CompleteMultipartUpload (3 round trips) instead of one PutObject, the
 * streaming variant would lose on pure network overhead for reasons that have
 * nothing to do with streaming. So: buffer locally, and only ever initiate a
 * real multipart upload if the buffer actually fills past partSizeBytes.
 * Otherwise, on finish(), a single sized PutObject.
 */
public final class StreamingPartUploadOutputStream implements AutoCloseable {

    private final S3Client s3;
    private final String bucket;
    private final String key;
    private final long partSizeBytes;

    /**
     * Exposes the backing array so a full part can be uploaded without a defensive
     * toByteArray() copy - see writeSegment()'s doc for why the copy count matters here.
     * Safe because every read of buf()/count() below is immediately followed by either an
     * upload of that exact range or a reset(), all on the single thread libvips writes from.
     */
    private static final class ExposedByteArrayOutputStream extends ByteArrayOutputStream {
        ExposedByteArrayOutputStream(int size) { super(size); }
        byte[] backingArray() { return buf; }
        int size0() { return count; }
    }

    private final ExposedByteArrayOutputStream buffer;
    /**
     * Reused across callbacks so the write path allocates nothing per call. Grows on demand;
     * libvips' target buffer is 8500 bytes, so in practice it is sized once and never again.
     */
    private byte[] scratch = new byte[16 * 1024];
    private String uploadId;
    private final List<CompletedPart> completedParts = new ArrayList<>();
    private int nextPartNumber = 1;
    private long totalBytesWritten = 0;
    private boolean completed = false;

    public StreamingPartUploadOutputStream(S3Client s3, String bucket, String key, long partSizeBytes) {
        this.s3 = s3;
        this.bucket = bucket;
        this.key = key;
        this.partSizeBytes = partSizeBytes;
        this.buffer = new ExposedByteArrayOutputStream((int) Math.min(partSizeBytes, Integer.MAX_VALUE));
    }

    /**
     * Called from the VCustomTarget WriteCallback. seg is already sliced to its exact length by
     * the library.
     * <p>
     * Copies via a reused scratch array rather than seg.toArray(), which allocated a fresh byte[]
     * on EVERY callback. libvips flushes its target in ~8500-byte chunks, so a 1.7MB bleed output
     * produced ~200 short-lived arrays per job - times queue size, times reps. That garbage is
     * exactly what the gc columns were reporting: the str-output rows showed 99ms of GC pause
     * against 0ms for the equivalent buffered-output row, while being 0.5-1% slower for no
     * reason connected to streaming.
     */
    public long writeSegment(MemorySegment seg) throws IOException {
        int n = (int) seg.byteSize();
        if (n > scratch.length) {
            scratch = new byte[Integer.highestOneBit(n - 1) << 1];
        }
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0L, scratch, 0, n);
        buffer.write(scratch, 0, n);
        totalBytesWritten += n;
        if (buffer.size() >= partSizeBytes) {
            flushAsPart();
        }
        return n; // must equal bytes consumed, or libvips treats it as an error
    }

    private void flushAsPart() throws IOException {
        if (uploadId == null) {
            CreateMultipartUploadResponse createResp = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build());
            uploadId = createResp.uploadId();
        }
        // Stream straight off the backing array - toByteArray() here was a third full copy of
        // every part, on top of the per-callback array and the copy into this buffer.
        int len = buffer.size0();
        UploadPartResponse resp = s3.uploadPart(
            UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId)
                .partNumber(nextPartNumber)
                .contentLength((long) len)
                .build(),
            RequestBody.fromInputStream(new ByteArrayInputStream(buffer.backingArray(), 0, len), len));
        completedParts.add(CompletedPart.builder().partNumber(nextPartNumber).eTag(resp.eTag()).build());
        nextPartNumber++;
        buffer.reset();
    }

    /** Called from the VCustomTarget EndCallback once libvips has written everything. */
    public void finish() throws IOException {
        if (uploadId == null) {
            // Fast path: buffer never crossed the threshold - single sized PutObject,
            // avoiding 3 round trips for what is, in practice, almost every job. Same
            // no-extra-copy treatment as flushAsPart().
            int len = buffer.size0();
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentLength((long) len).build(),
                RequestBody.fromInputStream(new ByteArrayInputStream(buffer.backingArray(), 0, len), len));
        } else {
            if (buffer.size() > 0) {
                flushAsPart(); // final part, may be smaller than partSizeBytes - S3 allows that for the last part
            }
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build());
        }
        completed = true;
    }

    public long totalBytesWritten() {
        return totalBytesWritten;
    }

    @Override
    public void close() {
        // Abort any in-flight multipart upload on the exception path - otherwise
        // failed benchmark runs silently accrue billable orphaned parts.
        if (uploadId != null && !completed) {
            try {
                s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId).build());
            } catch (Exception ignored) {}
        }
    }
}
