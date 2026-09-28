/*
 * SHILLGRAM: SHILLVPN built into the app.
 */
package io.github.audit0.shillgram.vpn;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import javax.net.SocketFactory;

/**
 * Plain sockets that reach their target through the local Xray SOCKS5 port
 * with its login and password (RFC 1928 / RFC 1929). OkHttp then speaks
 * TLS over them as usual. The target host name goes to the proxy
 * unresolved, so the lookup happens behind the tunnel too; pair this with
 * {@link #singleUnresolved(String)} as the Dns.
 */
final class Socks5SocketFactory extends SocketFactory {

    private final int proxyPort;
    private final String user;
    private final String password;

    Socks5SocketFactory(int proxyPort, String user, String password) {
        this.proxyPort = proxyPort;
        this.user = user;
        this.password = password;
    }

    /** An address that only carries the host name to the proxy (a Dns for OkHttp). */
    static List<InetAddress> singleUnresolved(String host) throws UnknownHostException {
        return Collections.singletonList(InetAddress.getByAddress(host, new byte[] { 0, 0, 0, 0 }));
    }

    @Override
    public Socket createSocket() {
        return new TunnelSocket();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        final Socket socket = createSocket();
        socket.connect(InetSocketAddress.createUnresolved(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        final Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return createSocket(address, port);
    }

    private final class TunnelSocket extends Socket {
        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress)) {
                throw new IOException("unsupported address");
            }
            final InetSocketAddress target = (InetSocketAddress) endpoint;
            super.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), proxyPort), timeout);
            final int previousTimeout = getSoTimeout();
            setSoTimeout(timeout > 0 ? timeout : 15000);
            try {
                handshake(target);
            } catch (IOException e) {
                close();
                throw e;
            } finally {
                if (!isClosed()) {
                    setSoTimeout(previousTimeout);
                }
            }
        }

        private void handshake(InetSocketAddress target) throws IOException {
            final InputStream in = getInputStream();
            final OutputStream out = getOutputStream();
            // Greeting: version 5, one method, username/password.
            out.write(new byte[] { 5, 1, 2 });
            out.flush();
            if (readByte(in) != 5 || readByte(in) != 2) {
                throw new IOException("socks: method refused");
            }
            final byte[] name = user.getBytes(StandardCharsets.UTF_8);
            final byte[] pass = password.getBytes(StandardCharsets.UTF_8);
            final byte[] auth = new byte[3 + name.length + pass.length];
            auth[0] = 1;
            auth[1] = (byte) name.length;
            System.arraycopy(name, 0, auth, 2, name.length);
            auth[2 + name.length] = (byte) pass.length;
            System.arraycopy(pass, 0, auth, 3 + name.length, pass.length);
            out.write(auth);
            out.flush();
            if (readByte(in) != 1 || readByte(in) != 0) {
                throw new IOException("socks: auth refused");
            }
            // CONNECT by domain name.
            final byte[] host = target.getHostString().getBytes(StandardCharsets.US_ASCII);
            if (host.length == 0 || host.length > 255) {
                throw new IOException("socks: bad host");
            }
            final byte[] request = new byte[7 + host.length];
            request[0] = 5;
            request[1] = 1;
            request[2] = 0;
            request[3] = 3;
            request[4] = (byte) host.length;
            System.arraycopy(host, 0, request, 5, host.length);
            request[5 + host.length] = (byte) ((target.getPort() >> 8) & 0xFF);
            request[6 + host.length] = (byte) (target.getPort() & 0xFF);
            out.write(request);
            out.flush();
            if (readByte(in) != 5 || readByte(in) != 0) {
                throw new IOException("socks: connect refused");
            }
            readByte(in); // Reserved.
            final int type = readByte(in);
            final int skip;
            if (type == 1) {
                skip = 4;
            } else if (type == 4) {
                skip = 16;
            } else if (type == 3) {
                skip = readByte(in);
            } else {
                throw new IOException("socks: bad reply");
            }
            for (int i = 0; i < skip + 2; i++) {
                readByte(in);
            }
        }

        private int readByte(InputStream in) throws IOException {
            final int value = in.read();
            if (value < 0) {
                throw new IOException("socks: closed");
            }
            return value;
        }
    }
}
