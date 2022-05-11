package edu.umd.cs.ztorrent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an HTTP response.
 * Tutorialspoint: HTTP responses consist of:
 * - A Status-line
 * - Zero or more header (General|Response|Entity) fields followed by CRLF
 * - An empty line (i.e., a line with nothing preceding the CRLF) indicating the end of the header fields
 * - Optionally a message-body
 */
public class HTTPResponse {
    public enum HeaderType {HTTP, Date, Server, LastModified, Etag, ContentType, ContentLength, Connection, Pragma, TransferEncoding, UNKNOWN}
    public final Map<HeaderType, String> headerMap;
    public int status = -1;
    public String version;
    public int contentSize = -1;
    public byte[] body;
    public boolean transferEncoding;

    public HTTPResponse(InputStream in) throws IOException {
        String patternStr = "[0-9]+";
        transferEncoding = false;
        Pattern pattern = Pattern.compile(patternStr);
        headerMap = new HashMap<>();
        boolean emptyFound = false;
        byte[] dataIn;
        while (!emptyFound) {
            dataIn = readHeader(in);
            String p = new String(dataIn, StandardCharsets.UTF_8);

            if (p == null) {
                throw new RuntimeException("[ERROR]: HttpResponse Parse Error");
            }

            if (p.contentEquals("\r\n")) {
                emptyFound = true;
            } else if (p.startsWith("HTTP")) {
                // Parse status and version
                int i = p.indexOf(" ");
                version = p.subSequence(5, i).toString();

                Matcher matcher = pattern.matcher(p.subSequence(i + 1, p.length()));
                if (matcher.find()) {
                    status = new Integer(p.substring(i + 1 + matcher.start(), i + 1 + matcher.end()));
                }

                headerMap.put(HeaderType.ContentLength, p.substring(4).replaceAll(("[\\n\\r]"), ""));
            } else if (p.startsWith("Date:")) {
                headerMap.put(HeaderType.Date, p.substring(5));
            } else if (p.startsWith("Sever:")) {
                headerMap.put(HeaderType.Server, p.substring(6));
            } else if (p.startsWith("Last-Modified:")) {
                headerMap.put(HeaderType.LastModified, p.substring(14));
            } else if (p.startsWith("ETag:")) {
                headerMap.put(HeaderType.Etag, p.substring(5));
            } else if (p.startsWith("Content-Type:")) {
                headerMap.put(HeaderType.ContentType, p.substring(13));
            } else if (p.startsWith("Content-Length:")) {
                headerMap.put(HeaderType.ContentLength, p.substring(15).replaceAll(("[\\s\\n\\r]"), ""));
                contentSize = new Integer(p.substring(15).replaceAll(("[\\s\\n\\r]"), ""));
            } else if (p.startsWith("Connection:")) {
                headerMap.put(HeaderType.Connection, p.substring(11));
            } else if (p.startsWith("Pragma:")) {
                headerMap.put(HeaderType.Pragma, p.substring(7));
            } else if (p.startsWith("Transfer-Encoding:")) {
                transferEncoding = true;
                headerMap.put(HeaderType.TransferEncoding, p.substring(18).trim());
            } else {
                System.out.println("[ERROR] Unknown header \"" + p.substring(0, p.length() - 2) + "\"");
                String o = p;
                if (headerMap.containsKey(HeaderType.UNKNOWN)) {
                    o = headerMap.get(HeaderType.UNKNOWN) + p;
                }
                headerMap.put(HeaderType.UNKNOWN, o);
            }
        }

        if (contentSize == -1 && !transferEncoding) {
            System.out.println("[WARNING] Reading MAX_VALUE bytes");
            contentSize = Integer.MAX_VALUE;
            body = readBody(in, contentSize, false);
        } else if (transferEncoding) {
            if (headerMap.get(HeaderType.TransferEncoding).equals("chunked")) {
                body = readBody(in, contentSize, true);
            } else {
                throw new RuntimeException("[ERROR]: RuntimeException in HttpResponse");
            }
        } else {
            body = readBody(in, contentSize, false);
        }
    }

    /**
     * Reads in the response header fields (General|Response|Entity) followed by CRLF.
     * @param inputStream
     * @return
     * @throws IOException
     */
    public static byte[] readHeader(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int ch;
        int lastCh = 0;
        try {
            while ((ch = inputStream.read()) >= 0) {
                buf.write(ch);
                if (ch == '\n' && lastCh == '\r') {
                    break;
                }
                lastCh = ch;
            }
        } catch (SocketTimeoutException STE) {
        }
        if (buf.size() == 0) {
            return null;
        }
        return buf.toByteArray();
    }

    /**
     * Reads in an HTTP response body.  Boolean option for chunked transfer encoding.
     * Wikipedia: Chunked transfer encoding is a streaming data transfer mechanism available in version 1.1 of the Hypertext Transfer Protocol (HTTP).
     * In chunked transfer encoding, the data stream is divided into a series of non-overlapping "chunks".
     * The chunks are sent out and received independently of one another. No knowledge of the data stream outside the currently-being-processed chunk is necessary for both the sender and the receiver at any given time.
     * Each chunk is preceded by its size in bytes. The transmission ends when a zero-length chunk is received.
     *
     * @param inputStream
     * @param bytes
     * @param chunked
     * @return
     * @throws IOException
     */
    public static byte[] readBody(InputStream inputStream, long bytes, boolean chunked) throws IOException {
        int ch;
        long bytesRead = 0;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        if (chunked) {
            ByteArrayOutputStream temp = new ByteArrayOutputStream();

            try {
                while (true) {
                    int lastCh = 0;
                    while ((ch = inputStream.read()) >= 0) {
                        temp.write(ch);
                        if (ch == '\n' && lastCh == '\r') {
                            break;
                        }
                        lastCh = ch;
                    }
                    String s = temp.toString().replaceAll(("[\\n\\r]"), "");
                    long size = Long.parseLong(s, 16);
                    temp.reset();

                    if (size == 0) {
                        break;
                    }

                    while (bytesRead < size && (ch = inputStream.read()) >= 0) {
                        buf.write(ch);
                        bytesRead++;
                    }

                    ch = inputStream.read();
                    ch = inputStream.read();
                }
            } catch (SocketTimeoutException STE) {
                System.out.println("[ERROR] SocketTimeoutException");
            }

        } else {
            try {
                while (bytesRead < bytes && (ch = inputStream.read()) >= 0) {
                    buf.write(ch);
                    bytesRead++;
                }
            } catch (SocketTimeoutException STE) {
                System.out.println("[ERROR] SocketTimeoutException");
            }

        }

        if (buf.size() == 0) {
            return new byte[0];
        }
        return buf.toByteArray();
    }
}
