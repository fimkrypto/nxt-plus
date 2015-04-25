package nxt.peer;

import nxt.Nxt;
import nxt.util.Logger;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * PeerWebSocket represents an HTTP/HTTPS upgraded connection
 *
 * Peer connection messages are POST requests/responses in JSON
 * format.  The JSON string is prefixed with control fields that are
 * used to manage the WebSocket connection.  The message prefix
 * begins with '(' and ends with ')' and consists of comma-separated
 * fields.
 *
 * The Version 1 message prefix has the following format: (version,id,flags,length)
 *   - 'version' is the message version (Integer)
 *   - 'id' is the request identifier (Long)
 *   - 'flags' is a bit field of flags (Integer)
 *   - 'length' is the uncompressed message length (Integer)
 */
@WebSocket
public class PeerWebSocket {

    /** Message compression enabled */
    private static final boolean isGzipEnabled = Nxt.getBooleanProperty("nxt.enablePeerServerGZIPFilter");

    /** Maximum message size */
    static final int MAX_MESSAGE_SIZE = 192*1024*1024;

    /** Minimum compressed message size */
    private static final int MIN_COMPRESS_SIZE = 256;

    /** Compressed message flag */
    private static final int FLAG_COMPRESSED = 1;

    /** Our WebSocket message version */
    private static final int VERSION = 1;

    /** Negotiated WebSocket message version */
    private int version = VERSION;

    /** Thread pool for server request processing */
    private static final ThreadPoolExecutor threadPool =
            new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors(),
                                   Runtime.getRuntime().availableProcessors()*4,
                                   60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    /** WebSocket session */
    private Session session;

    /** WebSocket endpoint - set for an accepted connection */
    private PeerServlet peerServlet;

    /** WebSocket client - set for an initiated connection */
    private WebSocketClient peerClient;

    /** WebSocket lock */
    private final ReentrantLock lock = new ReentrantLock();

    /** Pending POST request map */
    private final ConcurrentHashMap<Long, PostRequest> requestMap = new ConcurrentHashMap<>();

    /** Next POST request identifier */
    private long nextRequestId = 0;

    /** WebSocket connection timestamp */
    private long connectTime = 0;

    /**
     * Create a client socket
     */
    public PeerWebSocket() {
        peerClient = new WebSocketClient();
        peerClient.getPolicy().setIdleTimeout(Peers.webSocketIdleTimeout);
        peerClient.getPolicy().setMaxBinaryMessageSize(MAX_MESSAGE_SIZE);
    }

    /**
     * Create a server socket
     *
     * @param   peerServlet         Servlet for request processing
     */
    public PeerWebSocket(PeerServlet peerServlet) {
        this.peerServlet = peerServlet;
    }

    /**
     * Start a client session
     *
     * @param   uri                 Server URI
     * @return                      TRUE if the WebSocket connection was completed
     * @throws  IOException         I/O error occurred
     */
    public boolean startClient(URI uri) throws IOException {
        String address = String.format("%s:%d", uri.getHost(), uri.getPort());
        boolean useWebSocket = false;
        //
        // Create a WebSocket connection.  We need to serialize the connection requests
        // since the NRS server will issue multiple concurrent requests to the same peer.
        // After a successful connection, the subsequent connection requests will return
        // immediately.  After an unsuccessful connection, a new connect attempt will not
        // be done until 60 seconds have passed.
        //
        lock.lock();
        try {
            if (session != null) {
                useWebSocket = true;
            } else if (System.currentTimeMillis() > connectTime+10*1000) {
                connectTime = System.currentTimeMillis();
                if (!peerClient.isStarting() && !peerClient.isStarted())
                    peerClient.start();
                peerClient.setConnectTimeout(Peers.connectTimeout);
                ClientUpgradeRequest req = new ClientUpgradeRequest();
                Future<Session> conn = peerClient.connect(this, uri, req);
                session = conn.get(Peers.connectTimeout+100, TimeUnit.MILLISECONDS);
                useWebSocket = true;
            }
        } catch (ExecutionException exc) {
            if (exc.getCause() instanceof UpgradeException) {
                // We will use HTTP
            } else if (exc.getCause() instanceof IOException) {
                // Report I/O exception
                throw (IOException)exc.getCause();
            } else {
                // We will use HTTP
                Logger.logDebugMessage(String.format("WebSocket connection to %s failed", address), exc);
            }
        } catch (TimeoutException exc) {
            throw new SocketTimeoutException(String.format("WebSocket connection to %s timed out", address));
        } catch (Exception exc) {
            Logger.logDebugMessage(String.format("WebSocket connection to %s failed", address), exc);
        } finally {
            if (!useWebSocket)
                close();
            lock.unlock();
        }
        return useWebSocket;
    }

    /**
     * WebSocket connection complete
     *
     * @param   session             WebSocket session
     */
    @OnWebSocketConnect
    public void onConnect(Session session) {
        this.session = session;
        Logger.logDebugMessage(String.format("%s WebSocket connection with %s completed",
                                             peerServlet!=null ? "Inbound" : "Outbound",
                                             session.getRemoteAddress().getHostString()));
    }

    /**
     * Return the WebSocket session for this connection
     *
     * @return                      WebSocket session or null if there is no session
     */
    public Session getSession() {
        return session;
    }

    /**
     * Process a POST request by sending the request message and then
     * waiting for a response.  This method is used by the connection
     * originator.
     *
     * @param   request             Request message
     * @return                      Response message
     * @throws  IOException         I/O error occurred
     */
    public String doPost(String request) throws IOException {
        long requestId;
        //
        // Send the POST request
        //
        lock.lock();
        try {
            if (session == null || !session.isOpen())
                throw new IOException("WebSocket session is not open");
            requestId = nextRequestId++;
            byte[] requestBytes = request.getBytes("UTF-8");
            int requestLength = requestBytes.length;
            int flags = 0;
            if (isGzipEnabled && requestLength>=MIN_COMPRESS_SIZE) {
                flags |= FLAG_COMPRESSED;
                ByteArrayOutputStream outStream = new ByteArrayOutputStream(requestLength);
                try (GZIPOutputStream gzipStream = new GZIPOutputStream(outStream)) {
                    gzipStream.write(requestBytes);
                }
                requestBytes = outStream.toByteArray();
            }
            ByteBuffer buf = ByteBuffer.allocate(requestBytes.length+20);
            buf.putInt(version)
               .putLong(requestId)
               .putInt(flags)
               .putInt(requestLength)
               .put(requestBytes)
               .flip();
            if (buf.limit() > MAX_MESSAGE_SIZE)
                throw new ProtocolException("POST request length exceeds max message size");
            session.getRemote().sendBytes(buf);
        } finally {
            lock.unlock();
        }
        //
        // Get the response
        //
        String response;
        try {
            PostRequest postRequest = new PostRequest();
            requestMap.put(requestId, postRequest);
            response = postRequest.get(Peers.readTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exc) {
            throw new IOException("WebSocket POST interrupted", exc);
        }
        return response;
    }

    /**
     * Send POST response
     *
     * This method is used by the connection acceptor to return the POST response
     *
     * @param   requestId           Request identifier
     * @param   response            Response message
     * @throws  IOException         I/O error occurred
     */
    public void sendResponse(long requestId, String response) throws IOException {
        lock.lock();
        try {
            if (session != null && session.isOpen()) {
                byte[] responseBytes = response.getBytes("UTF-8");
                int responseLength = responseBytes.length;
                int flags = 0;
                if (isGzipEnabled && responseLength>=MIN_COMPRESS_SIZE) {
                    flags |= FLAG_COMPRESSED;
                    ByteArrayOutputStream outStream = new ByteArrayOutputStream(responseLength);
                    try (GZIPOutputStream gzipStream = new GZIPOutputStream(outStream)) {
                        gzipStream.write(responseBytes);
                    }
                    responseBytes = outStream.toByteArray();
                }
                ByteBuffer buf = ByteBuffer.allocate(responseBytes.length+20);
                buf.putInt(version)
                   .putLong(requestId)
                   .putInt(flags)
                   .putInt(responseLength)
                   .put(responseBytes)
                   .flip();
                if (buf.limit() > MAX_MESSAGE_SIZE)
                    throw new ProtocolException("POST response length exceeds max message size");
                session.getRemote().sendBytes(buf);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Process a socket message
     *
     * @param   inbuf               Message buffer
     * @param   off                 Starting offset
     * @param   len                 Message length
     */
    @OnWebSocketMessage
    public void OnMessage(byte[] inbuf, int off, int len) {
        lock.lock();
        try {
            ByteBuffer buf = ByteBuffer.wrap(inbuf, off, len);
            version = Math.min(buf.getInt(), VERSION);
            Long requestId = buf.getLong();
            int flags = buf.getInt();
            int length = buf.getInt();
            byte[] msgBytes = new byte[buf.remaining()];
            buf.get(msgBytes);
            if ((flags&FLAG_COMPRESSED) != 0) {
                ByteArrayInputStream inStream = new ByteArrayInputStream(msgBytes);
                try (GZIPInputStream gzipStream = new GZIPInputStream(inStream, 1024)) {
                    msgBytes = new byte[length];
                    int offset = 0;
                    while (offset < msgBytes.length) {
                        int count = gzipStream.read(msgBytes, offset, msgBytes.length-offset);
                        if (count < 0)
                            throw new EOFException("End-of-data reading compressed data");
                        offset += count;
                    }
                }
            }
            String message = new String(msgBytes, "UTF-8");
            if (peerServlet != null) {
                threadPool.execute(() -> peerServlet.doPost(this, requestId, message));
            } else {
                PostRequest postRequest = requestMap.remove(requestId);
                if (postRequest != null)
                    postRequest.complete(message);
            }
        } catch (Exception exc) {
            Logger.logDebugMessage("Exception while processing WebSocket message", exc);
        } finally {
            lock.unlock();
        }
    }

    /**
     * WebSocket session has been closed
     *
     * @param   statusCode          Status code
     * @param   reason              Reason message
     */
    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
        lock.lock();
        try {
            if (session != null) {
                Logger.logDebugMessage(String.format("%s WebSocket connection with %s closed",
                                       peerServlet!=null ? "Inbound" : "Outbound",
                                       session.getRemoteAddress().getHostString()));
                session = null;
            }
            IOException exc = new IOException("WebSocket connection closed");
            Set<Map.Entry<Long, PostRequest>> requests = requestMap.entrySet();
            requests.stream().forEach((entry) -> entry.getValue().complete(exc));
            requestMap.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Close the WebSocket
     */
    public void close() {
        lock.lock();
        try {
            if (session != null && session.isOpen())
                session.close();
            if (peerClient != null && (peerClient.isStarting() || peerClient.isStarted()))
                peerClient.stop();
        } catch (Exception exc) {
            Logger.logDebugMessage("Exception while closing WebSocket", exc);
        } finally {
            lock.unlock();
        }
    }

    /**
     * POST request
     */
    private class PostRequest {

        /** Request latch */
        private final CountDownLatch latch = new CountDownLatch(1);

        /** Response message */
        private volatile String response;

        /** Socket exception */
        private volatile IOException exception;

        /**
         * Create a post request
         */
        public PostRequest() {
        }

        /**
         * Wait for the response
         *
         * The caller must hold the lock for the request condition
         *
         * @param   timeout                 Wait timeout
         * @param   unit                    Time unit
         * @return                          Response message
         * @throws  InterruptedException    Wait interrupted
         * @throws  IOException             I/O error occurred
         */
        public String get(long timeout, TimeUnit unit) throws InterruptedException, IOException {
            if (!latch.await(timeout, unit))
                throw new IOException("WebSocket read timeout exceeded");
            if (exception != null)
                throw exception;
            return response;
        }

        /**
         * Complete the request with a response message
         *
         * The caller must hold the lock for the request condition
         *
         * @param   response                Response message
         */
        public void complete(String response) {
            this.response = response;
            latch.countDown();
        }

        /**
         * Complete the request with an exception
         *
         * The caller must hold the lock for the request condition
         *
         * @param   IOException             I/O exception
         */
        public void complete(IOException exc) {
            exception = exc;
            latch.countDown();
        }
    }
}
