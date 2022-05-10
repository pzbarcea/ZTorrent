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
 * https://wiki.theory.org/index.php/BitTorrentSpecification#Tracker_Response
 * "The response message consists of the following:
 *	-A Status-Line (for example HTTP/1.1 200 OK, which indicates that the client's request succeeded)
 *	-Response Headers, such as Content-Type: text/html
 *	-An empty line
 *	-An optional message body"
 *
 * @author pzbarcea
 *
 */
public class HttpResponse {
    public enum HeaderType {HTTP, Date, Server, LastModified, Etag, ContentType, ContentLength, Connection, Pragma, TransferEncoding, UNKNOWN}
    public final Map<HeaderType, String> headerMap;
    public int status = -1;
    public String version;
    public int contentSize = -1;
    public byte[] body;
    public boolean transferEncoding;

    /**
     * Parses an HTTP response header.
     *
     * @throws IOException
     */
    public HttpResponse(InputStream in) throws IOException {
        String patternStr = "[0-9]+";
        transferEncoding = false;
        Pattern pattern = Pattern.compile(patternStr);
        headerMap = new HashMap<>();
        boolean emptyFound = false;
        byte[] dataIn;
        while (!emptyFound) {
            dataIn = readRawHeaderLine(in);
            String p = bytesToString(dataIn);

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
                System.out.println("Unknown Header: \"" + p.substring(0, p.length() - 2) + "\"");
                String o = p;
                if (headerMap.containsKey(HeaderType.UNKNOWN)) {
                    o = headerMap.get(HeaderType.UNKNOWN) + p;
                }
                headerMap.put(HeaderType.UNKNOWN, o);
            }
        }

        if (contentSize == -1 && !transferEncoding) {
            System.out.println("[WARNING]: Reading MAX_VALUE bytes");
            contentSize = Integer.MAX_VALUE;
            body = readBytes(in, contentSize);
        } else if (transferEncoding) {
            if ("chunked".equals(headerMap.get(HeaderType.TransferEncoding))) {
                body = readChunked(in);
            } else {
                throw new RuntimeException("[ERROR]: RuntimeException in HttpResponse");
            }
        } else {
            body = readBytes(in, contentSize);
        }


    }

    private static String bytesToString(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    public static byte[] readRawHeaderLine(InputStream inputStream) throws IOException {
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
            //It might be ok. We'll Try to recover from this.
        }
        if (buf.size() == 0) {
            return null;
        }
        return buf.toByteArray();
    }

    public static byte[] readBytes(InputStream inputStream, long bytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int ch;
        long bytesRead = 0;
        try {
            while (bytesRead < bytes && (ch = inputStream.read()) >= 0) {
                buf.write(ch);
                bytesRead++;
            }
        } catch (SocketTimeoutException STE) {
            System.out.println("[ERROR]: SocketTimeoutException");
        }

        if (buf.size() == 0) {
            return new byte[0];
        }
        return buf.toByteArray();
    }

    public static byte[] readChunked(InputStream inputStream) throws IOException {
        ByteArrayOutputStream temp = new ByteArrayOutputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int ch;
        long bytesRead = 0;
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
                String s = temp.toString();
                s = s.replaceAll(("[\\n\\r]"), "");
                long size = Long.parseLong(s, 16);
                temp.reset();

                // Break if we read the last chunk
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
            System.out.println("[ERROR]: SocketTimeoutException");
        }

        if (buf.size() == 0) {
            return new byte[0];
        }

        return buf.toByteArray();
    }
}
